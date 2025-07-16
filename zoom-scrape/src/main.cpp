#include <list>
#include <stdio.h>
#include <chrono>
#include <cstdlib>
#include <thread>
#include <mutex>
#include <fstream>
#include <iostream>
#include <functional>
#include <condition_variable>

#include <thalamus.pb.h>

#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Weverything"
#endif
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavdevice/avdevice.h>
#include <libavformat/avformat.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

#include <boost/beast/core.hpp>
#include <boost/beast/http.hpp>
#include <boost/beast/version.hpp>
#include <boost/asio/connect.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <boost/asio/ip/udp.hpp>
#include <boost/asio/ssl.hpp>

#ifdef __clang__
#pragma clang diagnostic pop
#endif

#ifdef _WIN32
#include <WinSock2.h>
#elif defined(__APPLE__)
#include <arpa/inet.h>
#else
#include <endian.h>
#define htonll(x) htobe64(x)
#include <signal.h>
#endif

using namespace std::chrono_literals;
using namespace std::placeholders;

struct FramePool {
  struct State {
    std::mutex mutex;
    std::vector<AVFrame*> pool;
    bool deleted = false;
  };
  std::shared_ptr<State> state = std::make_shared<State>();
  ~FramePool() {
    std::lock_guard<std::mutex> lock(state->mutex);
    state->deleted = true;
    for (auto t : state->pool) {
      av_frame_free(&t);
    }
  }
  std::shared_ptr<AVFrame> get() {
    std::lock_guard<std::mutex> lock(state->mutex);
    if (state->pool.empty()) {
      state->pool.push_back(av_frame_alloc());
    }
    auto result = state->pool.back();
    state->pool.pop_back();
    return std::shared_ptr<AVFrame>(result, [state_ref=this->state](AVFrame *t) {
      std::lock_guard<std::mutex> lock2(state_ref->mutex);
      if(state_ref->deleted) {
        av_frame_free(&t);
      } else {
        state_ref->pool.push_back(t);
      }
    });
  }
};

void sleeve_assert(bool condition, const std::string& text) {
  if(!condition) {
    std::cout << text << std::endl;
    std::abort();
  }
}
void sleeve_assert(bool condition) {
  if(!condition) {
    std::abort();
  }
}

struct Frame {
  std::chrono::steady_clock::time_point time;
  std::shared_ptr<AVFrame> frame;
};

struct Encoder {
  int width;
  int height;
  AVRational framerate;
  AVPixelFormat format;
  std::mutex mutex;
  std::condition_variable condition;
  std::jthread thread;
  std::list<Frame> jobs;
  std::string filename;
  std::ofstream output;
  Encoder(int width, int height, AVRational framerate, AVPixelFormat format) : width(width), height(height), framerate(framerate), format(format), output(std::ofstream("sleeve.tha")) {
    filename = "sleeve.tha." + std::to_string(std::chrono::system_clock::now().time_since_epoch().count()/1'000'000'000);
    output = std::ofstream(filename);
    thread = std::jthread(std::bind(&Encoder::target, this, _1));
  }
  ~Encoder() {
    thread.request_stop();
    thread.join();
    output.close();

    std::cout << "Uploading" << std::endl;

    std::ifstream uuid_file("/home/jarl/.sleeve/uuid.txt");
    std::string uuid;
    uuid_file >> uuid;
    while(uuid.back() == '\r' || uuid.back() == '\n') {
      uuid = uuid.substr(0, uuid.size()-1);
    }
    uuid_file.close();

    std::cout << "uuid=" << uuid << std::endl;

    boost::asio::io_context ioc;
    boost::asio::ssl::context ssl_context(boost::asio::ssl::context::sslv23_client);
    ssl_context.load_verify_file("sleeve_d50fb63dd19340d2b20aaebeeaf168d4.cer");
    boost::asio::ssl::stream<boost::asio::ip::tcp::socket> ssocket(ioc, ssl_context);

    boost::asio::ip::tcp::resolver resolver(ioc);
    auto const results = resolver.resolve("20.55.36.17", "443");

    boost::asio::connect(ssocket.lowest_layer(), results);
    ssocket.handshake(boost::asio::ssl::stream_base::handshake_type::client);

    std::string sep = "WebKitFormBoundary7MA4YWxkTrZu0gW";

    char buffer[1024];
    std::ifstream input(filename, std::ios_base::binary);
    input.seekg(0, std::ios_base::end);
    auto file_size = input.tellg();
    input.seekg(0, std::ios_base::beg);

    auto chunk_start = std::string("--") + sep + "\r\nContent-Disposition: form-data; name=\"" + filename + "\"; filename=\"" + filename + "\"\r\n\r\n";
    auto chunk_end = std::string("\r\n--") + sep + "--\r\n";
    auto content_length = chunk_start.size() + file_size + chunk_end.size();

    boost::system::error_code ec;
    std::string chunk = "POST /upload?uuid=" + uuid + " HTTP/1.1\r\n";
    boost::asio::write(ssocket, boost::asio::buffer(chunk.data(), chunk.size()));
    chunk = "Content-Length: " + std::to_string(content_length) + "\r\nContent-Type: multipart/form-data; boundary=" + sep + "\r\n\r\n";
    std::cout << chunk;
    boost::asio::write(ssocket, boost::asio::buffer(chunk.data(), chunk.size()));

    //chunk = std::string("--") + sep + "\r\nContent-Disposition: form-data; name=\"uuid\"\r\n\r\n" + uuid + "\r\n";
    //boost::asio::write(ssocket, boost::asio::buffer(chunk.data(), chunk.size()));


    std::cout << chunk_start;
    boost::asio::write(ssocket, boost::asio::buffer(chunk_start.data(), chunk_start.size()));
    //boost::beast::http::write(ssocket, sr, ec);
    //sleeve_assert(!ec || ec == boost::beast::http::error::need_buffer);

    auto accum = 0;
    while(!input.eof()) {
      input.read(buffer, sizeof(buffer));
      auto count = input.gcount();
      accum += count;
      std::cout << "count " << accum << std::endl;
      //req.body().data = buffer;
      //req.body().size = count;
      boost::asio::write(ssocket, boost::asio::buffer(buffer, count));
      //sleeve_assert(!ec || ec == boost::beast::http::error::need_buffer, ec.message());
    }

    std::cout << chunk_end;
    boost::asio::write(ssocket, boost::asio::buffer(chunk_end.data(), chunk_end.size()));

    ////boost::beast::http::request<boost::beast::http::buffer_body> req(boost::beast::http::verb::post, "/upload", 11);
    ////req.set(boost::beast::http::field::host, "20.55.36.17");

    ////auto sep = "----" + std::to_string(std::rand());
    //std::cout << "sep=" << sep << std::endl;
    ////req.set(boost::beast::http::field::content_type, std::string("multipart/form-data; boundary=") + sep);

    ////boost::beast::http::request_serializer<boost::beast::http::buffer_body, boost::beast::http::fields> sr{req};

    //req.body().data = nullptr;
    //req.body().more = true;
    ////boost::beast::http::write_header(ssocket, sr, ec);
    //sleeve_assert(!ec || ec == boost::beast::http::error::need_buffer);

    //chunk = std::string("--") + sep + "\r\nContent-Disposition: form-data; name=\"uuid\"\r\n\r\n" + uuid + "\r\n";
    //std::cout << "chunk=" << chunk;
    //req.body().data = chunk.data();
    //req.body().size = chunk.size();
    //req.body().more = true;
    //boost::asio::write(ssocket, boost::asio::buffer(chunk.data(), chunk.size()), ec);
    ////boost::beast::http::write(ssocket, sr, ec);
    //sleeve_assert(!ec || ec == boost::beast::http::error::need_buffer);

    //char buffer[1024];
    //std::ifstream input(filename);
    //input.seekg(0, std::ios_base::end);
    //auto file_size = input.tellg();
    //input.seekg(0, std::ios_base::beg);

    //chunk = std::string("--") + sep + "\r\nContent-Type: application/octet-stream\r\nContent-Length: " + std::to_string(file_size) + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n\r\n";
    //std::cout << "chunk=" << chunk;
    //req.body().data = chunk.data();
    //req.body().size = chunk.size();
    //req.body().more = true;
    //boost::asio::write(ssocket, boost::asio::buffer(chunk.data(), chunk.size()), ec);
    ////boost::beast::http::write(ssocket, sr, ec);
    //sleeve_assert(!ec || ec == boost::beast::http::error::need_buffer);

    //auto accum = 0;
    //while(!input.eof()) {
    //  input.read(buffer, sizeof(buffer));
    //  auto count = input.gcount();
    //  accum += count;
    //  std::cout << "count " << accum << std::endl;
    //  req.body().data = buffer;
    //  req.body().size = count;
    //  boost::beast::http::write(ssocket, sr, ec);
    //  sleeve_assert(!ec || ec == boost::beast::http::error::need_buffer, ec.message());
    //}

    //chunk = std::string("\r\n--") + sep + "--";
    //std::cout << "chunk=" << chunk;
    //req.body().data = chunk.data();
    //req.body().size = chunk.size();
    //req.body().more = false;
    //boost::asio::write(ssocket, boost::asio::buffer(chunk.data(), chunk.size()), ec);
    ////boost::beast::http::write(ssocket, sr, ec);


    boost::beast::flat_buffer res_buffer;
    boost::beast::http::response<boost::beast::http::dynamic_body> res;
    boost::beast::http::read(ssocket, res_buffer, res);
    std::cout << res << std::endl;
  }

  void push(Frame job) {
    std::lock_guard<std::mutex> lock(mutex);
    jobs.push_back(job);
    condition.notify_one();
  }

  void target(std::stop_token stop_token) {
    auto packet = av_packet_alloc();
    auto frame2 = av_frame_alloc();
    frame2->width = width;
    frame2->height = height;
    frame2->format = AV_PIX_FMT_YUV420P;
    auto ret = av_frame_get_buffer(frame2, 0);
    sleeve_assert(ret >= 0, "Could not allocate the video frame data");

    auto codec = avcodec_find_encoder(AV_CODEC_ID_MPEG4);
    sleeve_assert(codec, "avcodec_find_encoder failed");

    auto context = avcodec_alloc_context3(codec);
    sleeve_assert(context, "avcodec_alloc_context3 failed");

    //context->bit_rate = std::numeric_limits<int>::max();
    context->width = width;
    context->height = height;
    context->framerate = framerate;
    context->time_base = {framerate.den, framerate.num};
    context->gop_size = framerate.num / framerate.den;

    context->pix_fmt = AV_PIX_FMT_YUV420P;

    uint8_t *src_data[4], *dst_data[4];
    int src_linesize[4], dst_linesize[4];

    auto sws_context =
          sws_getContext(width, height, format, width, height, context->pix_fmt,
                         SWS_BILINEAR, nullptr, nullptr, nullptr);
    ret = av_image_alloc(src_data, src_linesize, width, height, format, 16);
    sleeve_assert(ret >= 0, "Could not allocate source image");
    ret = av_image_alloc(dst_data, dst_linesize, width, height,
                         context->pix_fmt, 16);
    //dst_data_0 = dst_data[0];
    sleeve_assert(ret >= 0, "Could not allocate destination image");

    ret = avcodec_open2(context, codec, nullptr);
    sleeve_assert(ret >= 0, std::string("Could not open codec: ") + std::to_string(ret));

    //ret = av_frame_get_buffer(frame, 0);
    //sleeve_assert(ret >= 0, "Could not allocate the video frame data");

    //sws_context =
    //    sws_getContext(width, height, format, width, height, context->pix_fmt,
    //                   SWS_BILINEAR, nullptr, nullptr, nullptr);
    //ret = av_image_alloc(src_data, src_linesize, width, height, format, 16);
    //THALAMUS_ASSERT(ret >= 0, "Could not allocate source image");
    //ret = av_image_alloc(dst_data, dst_linesize, width, height,
    //                     context->pix_fmt, 16);
    //dst_data_0 = dst_data[0];
    //THALAMUS_ASSERT(ret >= 0, "Could not allocate destination image");
    //
    std::chrono::nanoseconds frame_interval(
        std::chrono::nanoseconds::period::den * framerate.den /
        framerate.num);
    thalamus_grpc::StorageRecord compressed_record;
    compressed_record.set_node("Webcam");
    auto compressed_image = compressed_record.mutable_image();
    compressed_image->set_width(width);
    compressed_image->set_height(height);
    compressed_image->set_format(
        thalamus_grpc::Image::Format::Image_Format_MPEG4);
    compressed_image->set_frame_interval(frame_interval.count());
    compressed_image->set_last(true);
    compressed_image->set_bigendian(true);
    auto data = compressed_image->add_data();

    while(true) {
      std::unique_lock<std::mutex> lock(mutex);
      condition.wait(lock, [&]() { return stop_token.stop_requested() || !jobs.empty(); });
      if(stop_token.stop_requested()) {
        return;
      }

      auto frame = jobs.front();
      jobs.pop_front();
      ret = av_frame_make_writable(frame2);
      sleeve_assert(ret >= 0, "av_frame_make_writable failed");

      sws_scale(sws_context, frame.frame->data, frame.frame->linesize, 0, height,
                dst_data, dst_linesize);
      std::copy(std::begin(dst_data), std::end(dst_data), frame2->data);
      std::copy(std::begin(dst_linesize), std::end(dst_linesize),
                frame2->linesize);

      auto nanoseconds = std::chrono::duration_cast<std::chrono::nanoseconds>(frame.time.time_since_epoch());
      compressed_record.set_time(nanoseconds.count());

      ret = avcodec_send_frame(context, frame2);
      sleeve_assert(ret >= 0, "Error sending a frame for encoding");

      data->clear();
      while (ret >= 0) {
        ret = avcodec_receive_packet(context, packet);
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wold-style-cast"
#endif
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
          break;
        }
#ifdef __clang__
#pragma clang diagnostic pop
#endif
        sleeve_assert(ret >= 0, "Error during encoding");
        data->append(reinterpret_cast<char *>(packet->data),
                     size_t(packet->size));
        av_packet_unref(packet);

      }
      auto serialized = compressed_record.SerializePartialAsString();
      auto size = serialized.size();
      auto bigendian_size = htonll(size);
      auto size_bytes = reinterpret_cast<char *>(&bigendian_size);
      output.write(size_bytes, sizeof(bigendian_size));
      output.write(serialized.data(), size);
    }

    ret = avcodec_send_frame(context, nullptr);
    sleeve_assert(ret >= 0, "Error sending a nullptr for flushing");

    compressed_image->set_width(0);
    compressed_image->set_height(0);
    data->clear();
    while (ret >= 0) {
      ret = avcodec_receive_packet(context, packet);
#ifdef __clang__
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wold-style-cast"
#endif
      if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
        break;
      }
#ifdef __clang__
#pragma clang diagnostic pop
#endif
      sleeve_assert(ret >= 0, "Error during encoding");
      data->append(reinterpret_cast<char *>(packet->data),
                   size_t(packet->size));
      av_packet_unref(packet);
    }
    auto serialized = compressed_record.SerializePartialAsString();
    auto size = serialized.size();
    auto bigendian_size = htonll(size);
    auto size_bytes = reinterpret_cast<char *>(&bigendian_size);
    output.write(size_bytes, sizeof(bigendian_size));
    output.write(serialized.data(), size);
  }
};

std::atomic_bool running = true;

void my_handler(int s) {
  std::cout << "Ctrl-C" << std::endl;
  running = false;
}

int record(std::stop_token stop_token) {
#ifdef _WIN32
  std::cout << "Fix input name" << std::endl;
  std::abort();
  auto input_name = "/dev/video0";
  auto input_format_name = "dshow";
#else
  auto input_name = "/dev/video2";
  auto input_format_name = "v4l2";
#endif
  auto format_context = avformat_alloc_context();
  AVDictionary *options = nullptr;

  if (!format_context) {
    std::cout << "Failed to allocate AVFormatContext" << std::endl;
    std::abort();
  }

  auto input_format = av_find_input_format(input_format_name);
  sleeve_assert(input_format, std::string("Input Format not found ") + input_format_name);
  auto err = avformat_open_input(&format_context, input_name, input_format, &options);
  if (err < 0) {
    std::cout << "Failed to open input: " << input_name << std::endl;
    std::abort();
  }

  auto codec_context = avcodec_alloc_context3(nullptr);
  auto stream_index = av_find_best_stream(format_context, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  avcodec_parameters_to_context(codec_context, format_context->streams[stream_index]->codecpar);
  codec_context->pkt_timebase = format_context->streams[stream_index]->time_base;
  auto time_base = codec_context->pkt_timebase;
  auto av_frame_rate = format_context->streams[stream_index]->avg_frame_rate;
  std::chrono::nanoseconds new_frame_interval(
      std::chrono::nanoseconds::period::den * av_frame_rate.den /
      av_frame_rate.num);

  auto codec = avcodec_find_decoder(codec_context->codec_id);
  {
    AVDictionary *sub_options = nullptr;
    err = av_dict_copy(&sub_options, options, 0);
    if (err < 0) {
      std::cout << "Failed to copy options" << std::endl;
      return 1;
    }

    if (!av_dict_get(sub_options, "threads", nullptr, 0))
      av_dict_set(&sub_options, "threads", "auto", 0);

    av_dict_set(&sub_options, "flags", "+copy_opaque",
                AV_DICT_MULTIKEY);

    err = avcodec_open2(codec_context, codec, &sub_options);
    if (err < 0) {
      std::cout << "Failed to open codec context" << std::endl;
      return 1;
    }
  }

  auto packet = av_packet_alloc();

  std::unique_ptr<Encoder> encoder = nullptr;

  int64_t start_pts = -1;
  auto start_time = std::chrono::steady_clock::now();
  FramePool frame_pool;

  while (!stop_token.stop_requested()) {
    err = av_read_frame(format_context, packet);
    if (err < 0) {
      std::cout << "Failed to read frame, stopping Video capture" << std::endl;
      return 1;
    }
    if (packet->stream_index != stream_index) {
      av_packet_unref(packet);
      continue;
    }

    auto now = std::chrono::steady_clock::now();

    //auto sleep_time = 0ns;
    //if (start_pts < 0) {
    //  start_pts = packet->pts;
    //  start_time = now;
    //} else {
    //  auto pts =
    //      1'000'000'000 * (packet->pts - start_pts) * time_base.num;
    //  pts /= time_base.den;
    //  auto target = start_time + std::chrono::nanoseconds(pts);
    //  sleep_time = target - now;
    //  if (now < target) {
    //    std::this_thread::sleep_for(target - now);
    //  }
    //}

    err = avcodec_send_packet(codec_context, packet);
    if (err < 0) {
      std::cout << "Failed to decode frame, stopping Video capture" << std::endl;
      return 1;
    }
    av_packet_unref(packet);

    while (true) {
      auto frame = frame_pool.get();
      err = avcodec_receive_frame(codec_context, frame.get());
      if (err == AVERROR(EAGAIN)) {
        break;
      } else if (err < 0) {
        std::cout << "Failed to receive decode frame, stopping Video capture" << std::endl;
        return 1;
      }

      if (frame->pict_type == AV_PICTURE_TYPE_NONE) {
        continue;
      }
      auto now = std::chrono::steady_clock::now();

      if(!encoder) {
        encoder.reset(new Encoder(frame->width, frame->height, av_frame_rate, AVPixelFormat(frame->format)));
      }
      encoder->push(Frame{now, frame});
    }
  }
  std::cout << "Shutdown" << std::endl;
  return 0;
}

int main() {
  struct sigaction sigIntHandler;

  sigIntHandler.sa_handler = my_handler;
  sigemptyset(&sigIntHandler.sa_mask);
  sigIntHandler.sa_flags = 0;

  sigaction(SIGINT, &sigIntHandler, nullptr);

  avdevice_register_all();
  std::jthread capture_thread;

  boost::asio::io_context ioc;
  auto multicast_address = boost::asio::ip::address::from_string("224.0.0.91");
  boost::asio::ip::udp::endpoint endpoint(multicast_address, 50091);
  boost::asio::ip::udp::socket socket(ioc, endpoint.protocol());


  while(running) {
    std::cout << "LOOP" << std::endl;
    auto pipe = popen("lsof /dev/video2", "r");
    char buffer[1024];
    std::string result;
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result += buffer; // Append the read line to the result string
    }
    pclose(pipe);
    if(result.find("zoom") != std::string::npos)  {
      if(!capture_thread.joinable()) {
        std::cout << "Zoom started" << std::endl;
        capture_thread = std::jthread(std::bind(record, _1));
      }
    } else {
      if(capture_thread.joinable()) {
        std::cout << "Zoom stopped" << std::endl;
        capture_thread.request_stop();
        capture_thread.join();
      }
    }
    std::this_thread::sleep_for(1s);

    thalamus_grpc::StorageRecord record;
    auto message = record.mutable_analog();
    auto now = std::chrono::steady_clock::now();
    auto nanoseconds = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch());
    message->add_data(double(nanoseconds.count()));
    auto span = message->add_spans();
    span->set_name("Time");
    span->set_begin(0);
    span->set_end(1);
    message->add_sample_intervals(0);
    message->set_time(nanoseconds.count());
    record.set_time(nanoseconds.count());
    record.set_node("Laptop");
    auto serialized = record.SerializePartialAsString();
    socket.send_to(boost::asio::buffer(serialized), endpoint);
  }
  std::cout << "STOP" << std::endl;

  if(capture_thread.joinable()) {
    capture_thread.request_stop();
    capture_thread.join();
  }

  return 0;
}

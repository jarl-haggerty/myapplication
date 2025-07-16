FROM python:3.13

RUN useradd -ms /bin/bash sleeve
USER sleeve

WORKDIR /home/sleeve
COPY --chown=sleeve . .
RUN python -m venv venv
RUN venv/bin/pip install -r requirements.txt
EXPOSE 8080
CMD ["venv/bin/waitress-serve", "--call", "server:create_app"]

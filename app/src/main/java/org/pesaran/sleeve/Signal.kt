package org.pesaran.sleeve

class Signal1<T> {
    inner class Connection(val signal: Signal1<T>, val callback: (T) -> Unit) {
        fun disconnect() {
            signal.connections.remove(this)
        }
        operator fun invoke(t: T) {
          callback(t)
        }
    }
    private var connections = mutableListOf<Connection>();
    fun connect(listener: (T) -> Unit): Connection {
        val connection = Connection(this, listener)
        connections.add(connection)
        return connection
    }

    operator fun invoke(t: T) {
        connections.forEach{ it(t) }
    }
}

class Signal2<T1, T2> {
    inner class Connection(val signal: Signal2<T1, T2>, val callback: (T1, T2) -> Unit) {
        fun disconnect() {
            signal.connections.remove(this)
        }
        operator fun invoke(t1: T1, t2:T2) {
            callback(t1, t2)
        }
    }

    private var connections = mutableListOf<Connection>();
    fun connect(listener: (T1, T2) -> Unit): Connection {
        val connection = Connection(this, listener)
        connections.add(connection)
        return connection
    }

    operator fun invoke(t1: T1, t2: T2) {
        connections.forEach{ it(t1, t2) }
    }
}

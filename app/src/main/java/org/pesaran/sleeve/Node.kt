package org.pesaran.sleeve

interface Node {
    var ready: Signal1<Node>
}
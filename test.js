const net = require('net')
var socket = net.connect('8080')
socket.pipe(process.stdout)
process.stdin.pipe(socket)
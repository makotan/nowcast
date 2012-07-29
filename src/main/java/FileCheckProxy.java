/**
 * Created with IntelliJ IDEA.
 * User: makotan
 * Date: 12/07/27
 * Time: 20:38
 * To change this template use File | Settings | File Templates.
 */
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.*;
import org.vertx.java.core.http.impl.WebSocketMatcher;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.streams.Pump;
import org.vertx.java.deploy.Verticle;

import java.io.File;
import java.nio.file.*;
import java.util.UUID;

public class FileCheckProxy extends Verticle {

    private String host;
    private int port;
    private String remoteHost;
    private int remotePort;
    private final String FileEvent = "changeFile";
    private WebSocketMatcher wsMatcher = new WebSocketMatcher();
    private String checkPath = UUID.randomUUID().toString().replaceAll("-","");

    @Override
    public void start() throws Exception {
        init();
        HttpServer server = vertx.createHttpServer();

        final HttpClient client = vertx.createHttpClient();
        client.setPort(remotePort);
        client.setHost(remoteHost);
        client.setKeepAlive(true);

        server.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(final HttpServerRequest serverReq) {
                final Logger log = container.getLogger();
                final HttpClientRequest request = client.request(serverReq.method , serverReq.uri, new Handler<HttpClientResponse>() {
                    @Override
                    public void handle(final HttpClientResponse res) {
                        log.info("server responce statuscode:" + res.statusCode + " message:" + res.statusMessage);
                        HttpServerResponse response = serverReq.response;
                        response.statusCode = res.statusCode;
                        response.statusMessage = res.statusMessage;
                        response.headers().putAll(res.headers());
                        log.debug("headers " + response.headers());

						if(res.headers().containsKey("Content-Type") && res.headers().get("Content-Type").indexOf("text/html") > -1) {
                            fileCheckServerToClient(serverReq,res,response);
						} else {
                            pumpServerToClient(serverReq,res,response);
						}
                    }
                });
                pumpClientToServer(serverReq,request);
            }
        });

        wsMatcher.addPattern("/"+checkPath, new Handler<WebSocketMatcher.Match>() {
            @Override
            public void handle(WebSocketMatcher.Match match) {
                container.getLogger().info("connect filecheck");
                final ServerWebSocket socket = match.ws;
                final Handler<Message> file = new Handler<Message>() {
                    @Override
                    public void handle(Message message) {
                        container.getLogger().debug("update file");
                        socket.writeTextFrame("change");
                    }
                };
                vertx.eventBus().registerHandler(FileEvent, file);

                socket.closedHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void aVoid) {
                        vertx.eventBus().unregisterHandler(FileEvent, file);
                    }
                });
            }
        });
        server.websocketHandler(wsMatcher);

        server.listen(3004);
    }

    private void pumpClientToServer(final HttpServerRequest serverReq,final HttpClientRequest request) {
        final Logger log = container.getLogger();
        log.debug("call request method:" + serverReq.method + " url:" + serverReq.uri);
        final Pump reqpump = Pump.createPump(serverReq,request);
        serverReq.endHandler(new Handler<Void>() {
            @Override
            public void handle(Void arg0) {
                log.debug("call request handle end");
                reqpump.stop();
                request.end();
            }
        });
        request.headers().putAll(serverReq.headers());
        reqpump.start();
    }

    private void pumpServerToClient(final HttpServerRequest serverReq,HttpClientResponse res,HttpServerResponse response) {
        final Logger log = container.getLogger();
        if(response.headers().get("Transfer-Encoding") != null && response.headers().get("Transfer-Encoding").toString().equalsIgnoreCase("chunked")) {
            response.setChunked(true);
        }
        log.debug("to client response");
        final Pump respump = Pump.createPump(res, serverReq.response);
        res.endHandler(new Handler<Void>() {
            @Override
            public void handle(Void arg0) {
                log.debug("to client response end");
                respump.stop();
                serverReq.response.end();
            }
        });
        respump.start();
    }

    private void fileCheckServerToClient(final HttpServerRequest serverReq,HttpClientResponse res,HttpServerResponse response) {
        final Logger log = container.getLogger();
        log.debug("to client response html");
        if(response.headers().get("Transfer-Encoding") != null && response.headers().get("Transfer-Encoding").toString().equalsIgnoreCase("chunked")) {
            response.setChunked(true);
        }
        final String enc = "UTF-8"; // TODO それ以外にもに対応したい

        res.bodyHandler(new Handler<Buffer>() {
            @Override
            public void handle(Buffer buff) {
                log.debug("to client send");
                String html = buff.toString(enc);
                int bodyPos = html.indexOf("</body>");
                if (bodyPos <= 0) {
                    log.debug("to client send not insert script");
                    serverReq.response.end(buff);
                    return;
                }
                serverReq.response.headers().remove("Content-Length");
                String top = html.substring(0, bodyPos);
                String ins = script.replace("@server@", host + ":" + port + "/" + checkPath);
                String bottom = html.substring(bodyPos);
                Buffer buf = new Buffer(top);
                buf.appendString(ins,enc);
                buf.appendString(bottom, enc);
                serverReq.response.end(buf);
            }
        });
    }

    private void init() throws Exception {
        host = container.getConfig().getString("host", "localhost");
        port = container.getConfig().getNumber("port", 3004).intValue();
        remoteHost = container.getConfig().getString("remotehost", "localhost");
        remotePort = container.getConfig().getNumber("remoteport", 3003).intValue();
        initCheckDir();
    }

    private long fileCheckTimer = -1;
    protected void initCheckDir() throws Exception {
        if(fileCheckTimer != -1) {
            vertx.cancelTimer(fileCheckTimer);
        }
        int polingTime = container.getConfig().getNumber("polling", 100).intValue();
        Path rootPath = Paths.get("");
        final WatchService watcher = rootPath.getFileSystem().newWatchService();

        JsonArray checkdirJA = container.getConfig().getArray("checkdir");
        for(Object cd : checkdirJA.toArray()) {
            Path path = Paths.get(cd.toString());
            checkAddSubDirs(path,watcher);
        }

        fileCheckTimer = vertx.setPeriodic(polingTime,new Handler<Long>() {
            @Override
            public void handle(Long aLong) {
                WatchKey key = watcher.poll();
                if(key == null) return;
                // 中身は要らないからとりあえず捨てる
                key.pollEvents();
                key.reset();
                vertx.eventBus().publish(FileEvent,FileEvent);
            }
        });
    }

    protected void checkAddSubDirs(Path path,WatchService watcher) throws Exception {
        File file = path.toFile();
        if(file.isDirectory() == false) return;

        path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_DELETE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.OVERFLOW);

        File[] files = file.listFiles();
        for(File f : files) {
            checkAddSubDirs(f.toPath(),watcher);
        }
    }

    private final String script = "<script>" +
            "var ws = new WebSocket(\"ws://@server@\");\n" +
            "ws.onmessage = function(event){\n" +
            "location.reload();\n" +
            "}\n" +
            "</script>";

}

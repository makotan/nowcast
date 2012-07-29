/**
 * Created with IntelliJ IDEA.
 * User: makotan
 * Date: 12/07/26
 * Time: 21:39
 * To change this template use File | Settings | File Templates.
 */

import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.vertx.java.core.*;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.ServerWebSocket;
import org.vertx.java.core.http.impl.MimeMapping;
import org.vertx.java.core.http.impl.WebSocketMatcher;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.deploy.Verticle;

import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FileCheck extends Verticle {
    private String host;
    private Integer port;
    private String webRoot;
    private List<String> checkDir = new ArrayList<>();
    private final String FileEvent = "changeFile";
    private String enc;
    private WebSocketMatcher wsMatcher = new WebSocketMatcher();
    private String checkPath = UUID.randomUUID().toString().replaceAll("-","");

    @Override
    public void start() throws Exception {
        init();

        final HttpServer server = vertx.createHttpServer();

        server.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(final HttpServerRequest request) {
                try {
                    sendFile(request);
                } catch (Exception e) {
                    e.printStackTrace();
                }
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
                        container.getLogger().info("update file");
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

        server.listen(port);
    }

    private long fileCheckTimer = -1;
    protected void init() throws Exception {
        host = container.getConfig().getString("host","localhost");
        port = container.getConfig().getNumber("port", 3003).intValue();
        enc = container.getConfig().getString("enc","UTF-8");
        webRoot = container.getConfig().getString("webroot","webroot");
        initCheckDir();
    }

    protected void initCheckDir() throws Exception {
        if(fileCheckTimer != -1) {
            vertx.cancelTimer(fileCheckTimer);
        }
        int polingTime = container.getConfig().getNumber("polling", 100).intValue();
        Path rootPath = Paths.get(webRoot);
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

    protected void sendFile(final HttpServerRequest request) throws Exception {
        final String path = request.path;
        if(request.path.endsWith(".html") == false && request.path.endsWith("htm") == false) {
            request.response.sendFile(webRoot + path);
            return;
        }
        vertx.fileSystem().readFile(webRoot + path, new AsyncResultHandler<Buffer>() {
            @Override
            public void handle(AsyncResult<Buffer> buff) {
                String html = buff.result.toString(enc);
                int bodyPos = html.indexOf("</body>");
                if (bodyPos <= 0) {
                    request.response.sendFile(webRoot + path);
                    return;
                }
                int li = path.lastIndexOf('.');
                if (li != -1 && li != path.length() - 1) {
                    String ext = path.substring(li + 1, path.length());
                    String contentType = MimeMapping.getMimeTypeForExtension(ext);
                    if (contentType != null) {
                        request.response.headers().put(HttpHeaders.Names.CONTENT_TYPE, contentType);
                    }
                }
                String top = html.substring(0, bodyPos);
                String ins = script.replace("@server@", host + ":" + port + "/" + checkPath);
                String bottom = html.substring(bodyPos);
                request.response.end(top + ins + bottom);
            }
        });
    }
    private final String script = "<script>" +
            "var ws = new WebSocket(\"ws://@server@\");\n" +
            "ws.onmessage = function(event){\n" +
            "location.reload();\n" +
            "}\n" +
            "</script>";

}

import java.io.*;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {

    protected HashMap<Map<String, String>, Handler> handlers = new HashMap<>();

    public void listen(int port) {
        ExecutorService threadPool = Executors.newFixedThreadPool(64);
        Runnable logic = () -> createConnection(port);
        threadPool.submit(logic);
    }

    public void createConnection(int port) {
        final var validPaths = List.of("/index.html", "/spring.svg", "/spring.png", "/resources.html", "/styles.css", "/app.js", "/links.html", "/forms.html", "/classic.html", "/events.html", "/events.js");
        try (final var serverSocket = new ServerSocket(port)) {
            while (true) {
                try (
                        final var socket = serverSocket.accept();
                        final var in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        final var out = new BufferedOutputStream(socket.getOutputStream());
                ) {
                    // read only request line for simplicity
                    // must be in form GET /path HTTP/1.1
                    final var requestLine = in.readLine();
                    final var parts = requestLine.split(" ");

                    if (parts.length != 3) {
                        // just close socket
                        continue;
                    }

                    final var method = parts[0];
                    final var path = parts[1];
                    if (!validPaths.contains(path)) {
                        out.write((
                                "HTTP/1.1 404 Not Found\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n" +
                                        "\r\n"
                        ).getBytes());
                        out.flush();
                        continue;
                    }

                    Request request = new Request(method, path);
                    Handler handler = getHandlerByRequest(request);
                    handler.handle(request, out);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void addHandler(String method, String url, Handler handler) {
        HashMap<String, String> handlerInfo = new HashMap<>();
        handlerInfo.put(method, url);
        handlers.put(handlerInfo, handler);
    }

    public Handler getHandlerByRequest(Request request) {
        Map<String, String> keyPair = handlers.keySet().stream()
                .filter(x -> x.containsKey(request.getMethod()) && x.containsValue(request.getUrl()))
                .toList()
                .get(0);
        return handlers.entrySet()
                .stream()
                .filter(x -> x.getKey().equals(keyPair))
                .findFirst()
                .orElseThrow()
                .getValue();
    }
}
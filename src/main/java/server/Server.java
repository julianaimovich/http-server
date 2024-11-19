package server;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {

    protected String GET = "GET";
    protected String POST = "POST";
    protected List<String> allowedMethods = List.of(GET, POST);
    protected HashMap<Map<String, String>, Handler> handlers = new HashMap<>();

    public void listen(int port) {
        ExecutorService threadPool = Executors.newFixedThreadPool(64);
        Runnable logic = () -> createConnection(port);
        threadPool.submit(logic);
    }

    public void createConnection(int port) {
        try (final ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                try (
                        final Socket socket = serverSocket.accept();
                        final BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                        final BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
                ) {
                    Request request = new Request();
                    // лимит на request line + заголовки
                    final int limit = 4096;
                    in.mark(limit);
                    final byte[] buffer = new byte[limit];
                    final int read = in.read(buffer);

                    // ищем request line
                    final byte[] requestLineDelimiter = new byte[]{'\r', '\n'};
                    final int requestLineEnd = indexOf(buffer, requestLineDelimiter, 0, read);
                    if (requestLineEnd == -1) {
                        badRequest(out);
                        continue;
                    }

                    // читаем request line
                    final String[] requestLine = new String(Arrays.copyOf(buffer, requestLineEnd)).split(" ");
                    if (requestLine.length != 3) {
                        badRequest(out);
                        continue;
                    }

                    // получаем метод
                    final String method = requestLine[0];
                    if (!allowedMethods.contains(method)) {
                        badRequest(out);
                        continue;
                    }
                    request.setMethod(method);
                    System.out.println(method);

                    // получаем путь
                    final String path = requestLine[1];
                    if (!path.startsWith("/")) {
                        badRequest(out);
                        continue;
                    }
                    request.setPath(method);
                    System.out.println(path);

                    // ищем заголовки
                    final byte[] headersDelimiter = new byte[]{'\r', '\n', '\r', '\n'};
                    final int headersStart = requestLineEnd + requestLineDelimiter.length;
                    final int headersEnd = indexOf(buffer, headersDelimiter, headersStart, read);
                    if (headersEnd == -1) {
                        badRequest(out);
                        continue;
                    }

                    // отматываем на начало буфера
                    in.reset();
                    // пропускаем requestLine
                    in.skip(headersStart);

                    final byte[] headersBytes = in.readNBytes(headersEnd - headersStart);
                    final List<String> headers = Arrays.asList(new String(headersBytes).split("\r\n"));
                    System.out.println(headers);

                    // для GET тела нет
                    if (!method.equals(GET)) {
                        in.skip(headersDelimiter.length);
                        // вычитываем Content-Length, чтобы прочитать body
                        final Optional<String> contentLength = extractHeader(headers, "Content-Length");
                        if (contentLength.isPresent()) {
                            final int length = Integer.parseInt(contentLength.get());
                            final byte[] bodyBytes = in.readNBytes(length);
                            final String body = new String(bodyBytes);
                            request.setContentLength(length);
                            request.setBody(body);
                            System.out.println(body);
                        }
                    }

                    if (!getQueryParams(request).isEmpty()) {
                        request.setQueryParams(getQueryParams(request));
                    }

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

    private Handler getHandlerByRequest(Request request) {
        Map<String, String> keyPair = handlers.keySet().stream()
                .filter(x -> x.containsKey(request.getMethod()) && x.containsValue(request.getPath()))
                .toList()
                .get(0);
        return handlers.entrySet()
                .stream()
                .filter(x -> x.getKey().equals(keyPair))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private void badRequest(BufferedOutputStream out) throws IOException {
        out.write((
                "HTTP/1.1 400 Bad server.Request\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
        ).getBytes());
        out.flush();
    }

    private int indexOf(byte[] array, byte[] target, int start, int max) {
        outer:
        for (int i = start; i < max - target.length + 1; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static Optional<String> extractHeader(List<String> headers, String header) {
        return headers.stream()
                .filter(o -> o.startsWith(header))
                .map(o -> o.substring(o.indexOf(" ")))
                .map(String::trim)
                .findFirst();
    }

    private static String getQueryParam(Request request, String name) {
        return request.getQueryParams().stream()
                .filter(x -> x.getName().equals(name))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private static List<NameValuePair> getQueryParams(Request request) {
        URI uri = URI.create(request.getPath());
        return URLEncodedUtils.parse(uri, Charset.defaultCharset());
    }
}
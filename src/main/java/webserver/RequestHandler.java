package webserver;

import static util.IOUtils.*;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import db.DataBase;
import model.User;
import util.HttpRequestUtils;

public class RequestHandler extends Thread {
    private static final Logger log = LoggerFactory.getLogger(RequestHandler.class);

    private Socket connection;

    public RequestHandler(Socket connectionSocket) {
        this.connection = connectionSocket;
    }

    public void run() {
        log.debug("New Client Connect! Connected IP : {}, Port : {}", connection.getInetAddress(),
                connection.getPort());

        try (InputStream in = connection.getInputStream(); OutputStream out = connection.getOutputStream()) {
            // TODO 사용자 요청에 대한 처리는 이 곳에 구현하면 된다.
            BufferedReader buffer = new BufferedReader(new InputStreamReader(in));
            String line = buffer.readLine();
            if (line == null) {
                return;
            }

            log.debug(line);
            String[] firstHeader = line.split(" ");
            String method = firstHeader[0];
            String url = firstHeader[1];

            int endBodyIndex = 0;
            boolean logined = false;
            while (!line.equals("")) {
                line = buffer.readLine();
                log.debug(line);

                if (line.startsWith("Content-Length")) {
                    endBodyIndex = Integer.parseInt(line.replaceAll("Content-Length: ", ""));
                } else if (line.contains("logined")) {
                    logined = isLogin(line);
                }
            }

            Map<String, String> params = new HashMap<>();
            if (method.equalsIgnoreCase("GET") && url.indexOf("?") > 0) {
                int index = url.indexOf("?");
                String paramInfo = url.substring(index + 1);
                params = HttpRequestUtils.parseQueryString(paramInfo);
            }

            if (method.equalsIgnoreCase("POST")) {
                String paramInfo = readData(buffer, endBodyIndex);
                params = HttpRequestUtils.parseQueryString(paramInfo);
            }

            if (url.startsWith("/user/create")) {

                User user = new User(params.get("userId"), params.get("password"), params.get("name"),params.get("email"));
                log.debug("User : {}", user);
                DataBase.addUser(user);

                DataOutputStream dos = new DataOutputStream(out);
                response301Header(dos, "/index.html");
            } else if("/user/login".equals(url)) {
                User user = DataBase.findUserById(params.get("userId"));

                if (!params.isEmpty()) {
                    if (user == null) {
                        //로그인 실패
                        responseResourece(out, "/user/login_failed.html");
                        return;
                    }
                }
                if (user.getPassword().equals(params.get("password"))) {
                    DataOutputStream dos = new DataOutputStream(out);
                    response302LoginSuccessHeader(dos);
                } else {
                    responseResourece(out, "/user/login_failed.html");
                }

            } else if("/user/list".equals(url)) {
                if (!logined) {
                    responseResourece(out, "/user/login.html");
                    return;
                }

                Collection<User> users = DataBase.findAll();
                StringBuilder sb = new StringBuilder();
                sb.append("<table border='1'");
                for (User user: users) {
                    sb.append("<tr>");
                    sb.append("<td>" + user.getUserId() + "</td>");
                    sb.append("<td>" + user.getName() + "</td>");
                    sb.append("<td>" + user.getEmail() + "</td>");
                    sb.append("<tr>");
                }
                sb.append("</table>");
                byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
                DataOutputStream dos = new DataOutputStream(out);
                response200Header(dos, body.length);
                responseBody(dos, body);
            } else if (url.endsWith(".css")) {
                DataOutputStream dos = new DataOutputStream(out);
                byte[] body = Files.readAllBytes(new File(  "./webapp" + url).toPath());
                response200CssHeader(dos,body.length);
                responseBody(dos, body);

            } else if (url.startsWith("/")){
                responseResourece(out, url);
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private boolean isLogin(String line) {
        String[] headerTokens = line.split(":");
        Map<String, String> cookies = HttpRequestUtils.parseCookies(headerTokens[1].trim());
        String value = cookies.get("logined");
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(value);
    }

    private void response200CssHeader(DataOutputStream dos, int lengthOfBodyContent) {
        try {
            dos.writeBytes("HTTP/1.1 200 OK \r\n");
            dos.writeBytes("Content-Type: text/css;charset=utf-8\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private String getUrl(InputStream in) throws IOException {
        String url;
        try (BufferedReader buffer = new BufferedReader(new InputStreamReader(in))) {
            String httpHeader = buffer.lines().collect(Collectors.joining("\n"));
            System.out.println(httpHeader);
            url = httpHeader.split(" ")[1];
        }
        return url;
    }

    private void response200Header(DataOutputStream dos, int lengthOfBodyContent) {
        try {
            dos.writeBytes("HTTP/1.1 200 OK \r\n");
            dos.writeBytes("Content-Type: text/html;charset=utf-8\r\n");
            dos.writeBytes("Content-Length: " + lengthOfBodyContent + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private void response302LoginSuccessHeader(DataOutputStream dos) {
        try {
            dos.writeBytes("HTTP/1.1 302 Redirect \r\n");
            dos.writeBytes("Location: /index.html\r\n");
            dos.writeBytes("Set-Cookie: logined=true\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private void response301Header(DataOutputStream dos, String url) {
        try {
            dos.writeBytes("HTTP/1.1 301 Redirect \r\n");
            dos.writeBytes("Location: " + url + "\r\n");
            dos.writeBytes("\r\n");
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private void responseBody(DataOutputStream dos, byte[] body) {
        try {
            dos.write(body, 0, body.length);
            dos.flush();
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private void responseResourece(OutputStream out, String url) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        byte[] body = "Hello World".getBytes();
        File resourece = new File(  "./webapp" + url);
        if (resourece.exists()) {
            body = Files.readAllBytes(resourece.toPath());
        }
        response200Header(dos, body.length);
        responseBody(dos, body);
    }
}

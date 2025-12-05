package Chat;

import java.io.PrintWriter;
import java.net.Socket;


//서버에 접속한 유저들 관리
public class ConnectedUser {
    private String userName;
    private String userId;
    private PrintWriter out;
    private Socket socket;
    

    public ConnectedUser(String userName, String userId, PrintWriter out, Socket socket) {
        this.userName = userName;
        this.userId = userId;
        this.out = out;
        this.socket = socket;
    }

    //유저정보를 가져오는 메서드
    public String getUserName() { return userName; }
    public String getUserId() { return userId; }
    public PrintWriter getOut() { return out; }
    public Socket getSocket() { return socket; }
    
}
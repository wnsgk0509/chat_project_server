package Chat;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

//서버에 접속한 유저들 관리
public class ConnectedUser {
    private String userName;
    private String userId;
    private PrintWriter out;
    private Set<String> rooms = new HashSet<>();	//접속한 방 (중복 방지)

    public ConnectedUser(String userName, String userId, PrintWriter out) {
        this.userName = userName;
        this.userId = userId;
        this.out = out;
    }

    //유저정보를 가져오는 메서드
    public String getUserName() { return userName; }
    public String getUserId() { return userId; }
    public PrintWriter getOut() { return out; }

    //방추가, 제거, 본인이 들어있는지 여부,입장한 전체방
    public void addRoom(String roomName) { rooms.add(roomName); }
    public void removeRoom(String roomName) { rooms.remove(roomName); }
    public boolean isInRoom(String roomName) { return rooms.contains(roomName); }
    public Set<String> getRooms() { return rooms; }
}
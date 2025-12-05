package Chat;
import java.util.Set;
import java.util.HashSet;

// 회원 계정
public class UserAccount {
    private String name;
    private String id;
    private String pw;
    
    private Set<String> joinedRooms = new HashSet<>();

    public UserAccount(String name, String id, String pw) {
        this.name = name;
        this.id = id;
        this.pw = pw;
    }
    public String getName() { return name; }
    public String getId() { return id; }
    public String getPw() { return pw; }
    
    //  방 관리 기능들
    public void addRoom(String roomName) { joinedRooms.add(roomName); }
    public void removeRoom(String roomName) { joinedRooms.remove(roomName); }
    public boolean isInRoom(String roomName) { return joinedRooms.contains(roomName); }
    public Set<String> getJoinedRooms() { return joinedRooms; }
}
package Chat;


// 회원 계정
public class UserAccount {
    private String name;
    private String id;
    private String pw;

    public UserAccount(String name, String id, String pw) {
        this.name = name;
        this.id = id;
        this.pw = pw;
    }
    public String getName() { return name; }
    public String getId() { return id; }
    public String getPw() { return pw; }
}
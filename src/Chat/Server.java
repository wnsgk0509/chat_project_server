package Chat;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

public class Server {
	
	// 가입된 모든 계정 정보
	static Map<String, UserAccount> accounts 		= Collections.synchronizedMap(new HashMap<>());
	// 모든 접속자 리스트
	static List<ConnectedUser>	connectedUsers		= new CopyOnWriteArrayList<>();
	// 채팅방별 참여자 목록
	static Map<String, List<ConnectedUser>> rooms 	= Collections.synchronizedMap(new HashMap<>());
	// 대화 기록 보관함
	static Map<String, List<String>> roomMessages 	= Collections.synchronizedMap(new HashMap<>());


    public static void main(String[] args) {
    	
    	accounts.put("test1" , new UserAccount("user1" , "test1", "test1"));
    	accounts.put("test2" , new UserAccount("user2" , "test2", "test2"));
    	accounts.put("test3" , new UserAccount("user3" , "test3", "test3"));
    	accounts.put("test4" , new UserAccount("user4" , "test4", "test4"));
    	
    	
        try (ServerSocket serverSocket = new ServerSocket(8000)) {		//포트번호 8000번으로 생성
            System.out.println("Server Started...");

            while (true) {
                Socket socket = serverSocket.accept(); // 클라이언트 사용자 대기
                System.out.println("New client accepted: " + socket);
                new ClientThread(socket).start();	//스레드 실행
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ClientThread extends Thread {
        private ConnectedUser 	user;		//본인의 접속 데이터
        private Socket 			socket;		//소켓정보
        private PrintWriter 	out;		//입출력 스트림
        private BufferedReader 	in;
       

        public ClientThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in 	= new BufferedReader(new InputStreamReader(socket.getInputStream()));	//입출력 버퍼 호출
                out = new PrintWriter(socket.getOutputStream(), true);
                String msg;

                // ===== 로그인 처리 =====
                boolean authenticated = false;
                while (!authenticated) {
                    msg = in.readLine();	//클라이언트 입력을 기다림
                    if (msg == null) break;

                    JSONObject json = new JSONObject(msg);		//입력받은 데이터를(line) json 객체 저장해서
                    String cmd = json.getString("command").toUpperCase();	//json 객체 안에 있는 command 키의 값을 대문자로 변환해서 저장

                    if (cmd.equals("LOGIN")) {
                        authenticated = handleLogin(json);
                    } else {
                        sendJsonError("AUTH_REQUIRED", "Please log in or sign up first.", cmd);
                    }
                }

                if (!authenticated) {
                	msg = "";
                	return;	
                }

                // ===== 채팅/방 명령 처리 =====
               
                while ((msg = in.readLine()) != null) {
                    JSONObject json = new JSONObject(msg);	//데이터를 객체로 변환 후 저장
                 // 로그 찍기
                    System.out.println("=== 서버에서 받은 JSON ===");
                    System.out.println(json.toString());
                    
                    handleCommand(json);
                }
            } catch (IOException e) {
                System.out.println("Client exit: " + e.getMessage());
            } finally {
                off();
            }
        }
        //=======로그인 처리======
        private boolean handleLogin(JSONObject json) {
            String id = json.optString("id");	//id와pw의 값을 저장하고
            String pw = json.optString("pw");

            //유효성 검사
            if (!accounts.containsKey(id)) {	//회원계정에 id 가 없으면 반환
                sendJsonError("INVALID_CREDENTIALS", "Your ID or password is incorrect.", "LOGIN");
                return false;
            }

            UserAccount account = accounts.get(id);	
            
            if (!account.getPw().equals(pw)) {	//비밀번호가 같지 않으면
                sendJsonError("INVALID_CREDENTIALS", "Your ID or password is incorrect.", "LOGIN");
                return false;
            }

            
            // 접속자 명단에 등록
            user = new ConnectedUser(account.getName(), account.getId(), out);
            connectedUsers.add(user);

            // 로그인 성공
            JSONObject jsonRespone = new JSONObject();
            jsonRespone.put("status", "OK");
            jsonRespone.put("message", "Login Success");
            jsonRespone.put("command", "LOGIN");
            jsonRespone.put("username", user.getUserName());
            
            sendJson(jsonRespone);

            return true;
        }

        private void handleCommand(JSONObject json) {
            String cmd = json.optString("command").toUpperCase();	//command키 의 값을 대문자로 변경후 cmd 에 저장
            try {
                switch (cmd) {
                    case "CREATE": createRoom(json.optString("room")); break;
                    case "INDEX": indexRoom(json.optString("room")); break;
                    case "USER_LIST": sendUserList(json.optString("room")); break;
                    case "CHAT": 
                        String roomName = json.optString("room"); // 클라이언트에서 room 필드도 함께 보내야 함
                        String message = json.optString("message");
                        broadcastToRoom(roomName, message); 
                        break;
                    case "INVITE":
                        invite(json.optString("room"), json.optString("user"));
                        break;
                    case "INVITE_AVAILABLE_USERS": sendInviteAvailableUsers(json.optString("room")); break;
                    case "EXIT": exitRoom(json.optString("room")); break;
                    case "OFF": off(); break;
                    default: sendJsonError("UNKNOWN_COMMAND", "Unknown command.", cmd);
                }
            } catch (Exception e) {
                sendJsonError("SERVER", "Error processing command.", cmd);
                e.printStackTrace();
            }
        }

        // ===== 방 생성 =====
        private void createRoom(String roomName) {
        	//유효성 검사
            if (roomName.trim().isEmpty()) 		{ sendJsonError("INVALID_NAME", "Room name cannot be blank.", "CREATE"); return; }
            if (rooms.containsKey(roomName)) 	{ sendJsonError("ROOM_EXISTS", "This room already exists.", "CREATE"); return; }

            // 방 생성 및 유저 추가
            List<ConnectedUser> roomUsers = new CopyOnWriteArrayList<>();	//방에 접속한 유저 리스트 저장한 변수 선언 
            user.addRoom(roomName);		//본인의 데이터에도 방 이름 추가(입장)
            roomUsers.add(user);		//방 리스트에도 접속한 유저(본인) 정보 저장
            
            rooms.put(roomName, roomUsers);		//rooms 에도 데이터 갱신
            
            //  방별 메시지 리스트 초기화
            roomMessages.put(roomName, new ArrayList<>());	
            
            //클라이언트에 데이터 전송
            JSONObject json = new JSONObject();
            json.put("status", "OK");
            json.put("message", "Room '" + roomName + "' created");
            json.put("command", "CREATE");
            json.put("username", user.getUserName());
            json.put("room", roomName);
            sendJson(json);
        }

        //======채팅 이력 =====
        private void indexRoom(String roomName) {
            // 방 존재 체크 및 사용자 추가 (putIfAbsent 스레드 동시성 위험 방지)
            rooms.putIfAbsent(roomName, new CopyOnWriteArrayList<>());	//rooms에 데이터중 요청받은 방이름 배열 생성
            List<ConnectedUser> roomUsers = rooms.get(roomName);	//해당 방에 있는 모든 사용자의 정보를 가지게 된다
            
            if (!roomUsers.contains(user)) {	//본인이 방 목록에 없으면 
                roomUsers.add(user);			//추가(방 목록
                user.addRoom(roomName);			//		,본인 데이터)
            }

            // 채팅 이력 가져오기 (getOrDefault 사용하지 않으면 null 에러 발생) 
            List<String> chatHistory = roomMessages.getOrDefault(roomName, new ArrayList<>());	//해당 방에 대화 목록을 저장

            // JSON 배열 생성
            JSONArray historyArray = new JSONArray();		//채팅이력을 배열로 저장할 객체 생성
            for (String pastMsg : chatHistory) {			//과거 메시지 이력 만큼 반복
                try {
                    // pastMsg 가 JSON 형태의 문자열일시 객체로 변환 후 저장
                    JSONObject msgJson = new JSONObject(pastMsg);
                    historyArray.put(msgJson);
                } catch (Exception e) {
                    // 만약 단순 문자열이면 그대로 추가
                    historyArray.put(pastMsg);
                }
            }

            //클라이언트에 전송 
            JSONObject sendJson = new JSONObject();
            sendJson.put("status", "OK");
            sendJson.put("command", "INDEX");
            sendJson.put("room", roomName);
            sendJson.put("history", historyArray);

            sendJsonToUser(user, sendJson);
        }
        
        // ===== 접속 유저 수, 이름 목록 전송 =====
        private void sendUserList(String roomName) {
            List<ConnectedUser> roomUsers = rooms.get(roomName);	//방에 있는 유저 목록에 저장하고
            JSONObject json = new JSONObject();	
            json.put("command", "USER_LIST");

            if (roomUsers == null) {		//방에 유저가 없을시
            	json.put("userCount", 0);
            	json.put("users", new JSONArray());
            	json.put("room", roomName);

            } else {		//방에 유저가 있다면
            	json.put("userCount", roomUsers.size());	//들어와 있는 유저 수
                JSONArray arr = new JSONArray();			//유저 이름이 여러개 들어올 수 있기때문에
                for (ConnectedUser u : roomUsers) {			//유저 수 만큼 순회해서 arr에 저장
                    arr.put(u.getUserName()); 				// for 문 완료시 arr 는 ["이름1","이름2"]
                }
                json.put("users", arr);
                json.put("room", roomName);

            }

            sendJson(json); // 요청한 사용자한테만 전송
        }
        // ===== 방 초대 =====
        private void invite(String roomName, String inviteUserName) {	
            // 사용자가 해당 roomName에 속해있는지 확인
            if (!user.isInRoom(roomName)) {
                sendJsonError("NOT_IN_ROOM", "You are not in this room.", "INVITE");
                return;
            }

            
            ConnectedUser invited = connectedUsers.stream()	// (모든 사용자 중에서) 초대하려는 사용자를 저장
                    .filter(u -> u.getUserName().equals(inviteUserName))	//u는 모든 사용자,
                    .findFirst()	//첫 번째로 찾은값 확인
                    .orElse(null);	//못 찾았으면 null

            if (invited == null) {
                sendJsonError("USER_NOT_FOUND", "User not found.", "INVITE");
                return;
            }

            if (inviteUserName.equals(user.getUserName())) {
                sendJsonError("SELF_INVITE", "Cannot invite yourself.", "INVITE");
                return;
            }

            List<ConnectedUser> roomUsers  = rooms.get(roomName);
            if (roomUsers  != null && roomUsers .contains(invited)) {
                sendJsonError("ALREADY_IN_ROOM", "User already in room.", "INVITE");
                return;
            }

            // 초대된 유저 데이터에 방 추가
            invited.addRoom(roomName);
            
            // 초대받은 사용자에게 개별 알림
            JSONObject jsonInvite = new JSONObject();
          	jsonInvite.put("status", "INFO");
          	jsonInvite.put("message", "You have been invited to '" + roomName + "'");
          	jsonInvite.put("command", "INVITE");
          	jsonInvite.put("username", user.getUserName());
          	jsonInvite.put("room", roomName);
          	sendJsonToUser(invited, jsonInvite);

            // 방 전체에 NOTICE (알림용)
            JSONObject notice = new JSONObject();
            notice.put("command", "CHAT");
            notice.put("status", "INFO");
            notice.put("room", roomName);
            notice.put("message", user.getUserName() + " invited " + inviteUserName);
            
            // 대화 기록에 시스템 알림 저장
            roomMessages.get(roomName).add(notice.toString());
            
            sendJsonToRoom(roomName, notice);
            
            if (roomUsers  != null) {
            	roomUsers .add(invited);	//방목록에도 초대된 유저 추가
            }
          

        }
        
     // ===== 초대 가능한 유저 리스트 반환 =====
        private void sendInviteAvailableUsers(String roomName) {
            JSONObject json = new JSONObject();
            json.put("command", "INVITE_AVAILABLE_USERS");
            json.put("room", roomName);

            JSONArray arr = new JSONArray();	//접속한 유저중 초대할 방에 없는 유저 이름 저장할 배열
            List<ConnectedUser> roomUsers = rooms.get(roomName);	//방에 있는 유저 리스트를 저장
            
            // 방에 없으면 추가
            for (ConnectedUser u : connectedUsers) {	// 접속한 유저만큼 순회

                if (roomUsers == null || !roomUsers.contains(u)) {		//유저 리스트에 없거나 포함 되어 있지 않으면
                    arr.put(u.getUserName());					//초대가능한 유저로 판단
                }
            }

            json.put("users", arr);
            json.put("userCount", arr.length());

            // 요청한 사용자에게만 전송
            sendJson(json);
        }



        // ===== 방 나가기 =====
        private void exitRoom(String roomName) {
            if (!user.isInRoom(roomName)) {
                sendJsonError("NOT_IN_ROOM", "You are not in this room.", "EXIT");
                return;
            }

            List<ConnectedUser> roomUsers = rooms.get(roomName);	// 전체방중 특정방에 유저 리스트를 저장
            if (roomUsers != null) {		//빈 방이 아니라면
                roomUsers.remove(user);		//유저 리스트에서 본인을 제거
                user.removeRoom(roomName);	//본인 데이터에도 방 이름 제거

                // 본인에게 EXIT_OK
                JSONObject exitOk = new JSONObject();
                exitOk.put("status", "OK");
                exitOk.put("message", "You have left the room.");
                exitOk.put("command", "EXIT_OK");
                exitOk.put("username", user.getUserName());
                exitOk.put("room", roomName);
                sendJson(exitOk);

                // 다른 유저들에게 NOTICE (시스템 메시지)
                JSONObject notice = new JSONObject();
                notice.put("command", "CHAT");
                notice.put("status", "INFO");
                notice.put("message", user.getUserName() + " exit.");             
                notice.put("username", user.getUserName());
                notice.put("room", roomName);
                
                // 대화 기록에 시스템 알림 저장
                roomMessages.get(roomName).add(notice.toString());
                
                sendJsonToRoom(roomName, notice);
                

                // 방이 비면 삭제
                if (roomUsers.isEmpty()) rooms.remove(roomName);
            }
        }


        // ===== 메시지 브로드캐스트 =====
        private void broadcastToRoom(String roomName, String chatMessage) {
            if (!user.isInRoom(roomName)) {	
                sendJsonError("NOT_IN_ROOM", "Enter the room first.", "CHAT");
                return;
            }

            JSONObject json = new JSONObject();
            json.put("status", "MSG");
            json.put("username", user.getUserName());
            json.put("message", chatMessage);
            json.put("command", "CHAT");
            json.put("room", roomName);
            
            roomMessages.get(roomName).add(json.toString());

            sendJsonToRoom(roomName, json);
        }

        //로그인 성공 및 에러 메시지 응답 (개별적인)
        private void sendJson(JSONObject json) {
        	//로그
        	System.out.println("=== 서버에서 보내는 JSON ===");
        	System.out.println(json.toString());
        	
            out.println(json.toString());
        }

        //채팅 메시지, 입장 퇴장 알림
        private void sendJsonToRoom(String roomName, JSONObject json) {
            List<ConnectedUser> roomUsers = rooms.get(roomName);
            if (roomUsers == null) return;

            for (ConnectedUser u : roomUsers) {	//방에 있는 유저만큰 반복
            	//로그
            	System.out.println("=== 서버에서 보내는 JSON ===");
            	System.out.println(json.toString());
            	
                u.getOut().println(json.toString());
            }
        }

        //지정된 사람(초대)
        private void sendJsonToUser(ConnectedUser u, JSONObject json) {
        	//로그
        	System.out.println("=== 서버에서 보내는 JSON ===");
        	System.out.println(json.toString());
        	System.out.println("초대한 대상: " + u.getUserName());
        	
            u.getOut().println(json.toString());
        }

        //에러 발생
        private void sendJsonError(String code, String msg, String command) {
            JSONObject json = new JSONObject();
            json.put("status", "ERROR");
            json.put("code", code);
            json.put("message", msg);
            json.put("command", command);
            sendJson(json);
        }



        private void off() {
            connectedUsers.remove(user);	//접속되어 있는 유저중 본인 제거

            if (user != null) {				//완벽히 제거 되지 않았으면
                for (String roomName : new HashSet<>(user.getRooms())) {  //본인이 참가한 모든 방 만큼 반복
                    List<ConnectedUser> roomUsers = rooms.get(roomName);	//유저 리스트를 저장	
                    if (roomUsers != null) {
                    	roomUsers.remove(user);								//유저 리스트에도 본인 제거
                        if (roomUsers.isEmpty()) rooms.remove(roomName);	//빈 방이면 방 삭제
                    }
                    user.removeRoom(roomName);								//본인이 접속한 방 모두 제거
                }
            }

            try {
                if (socket != null) socket.close();
                if (in != null) 	in.close();
                if (out != null) 	out.close();
            } catch (IOException e) { e.printStackTrace(); }

            if (user != null) System.out.println(user.getUserName() + " Connection terminated");
        }

    }
}

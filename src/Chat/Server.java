package Chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
//    	
//    	accounts.put("test1" , new UserAccount("user1" , "test1", "test1"));
//    	accounts.put("test2" , new UserAccount("user2" , "test2", "test2"));
//    	accounts.put("test3" , new UserAccount("user3" , "test3", "test3"));
//    	accounts.put("test4" , new UserAccount("user4" , "test4", "test4"));
    	
    	
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
       
        private UserAccount getMyAccount() {
            // 전역 변수 accounts 맵에서 내 계정 정보(방 목록 포함)를 가져옴
            return accounts.get(user.getUserId());
        }

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

                // ===== 명령 처리 =====
               
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
            String id = json.optString("id");
            String pw = json.optString("pw");

            UserDAO dao = new UserDAO();
            UserAccount account = dao.login(id, pw);
            
            if (!accounts.containsKey(id)) {
                sendJsonError("INVALID_CREDENTIALS", "This ID is not registered.", "LOGIN");
                return false;
            }

            accounts.put(account.getId(), account);
            
            if (!account.getPw().equals(pw)) {
                sendJsonError("INVALID_CREDENTIALS", "The password does not exist.", "LOGIN");
                return false;
            }

            // 접속자 객체 생성
            this.user = new ConnectedUser(account.getName(), account.getId(), out, socket);
            connectedUsers.add(this.user); 


            // =========================================================================
            // 오프라인 방 복구 로직
            // =========================================================================
            
            Set<String> myRooms = account.getJoinedRooms();

            // 방 목록이 존재할 때만 실행
            if (myRooms != null) {
                for (String roomName : myRooms) {
                    
                    // 1. 메모리에 있는 실시간 방 리스트 가져오기
                    List<ConnectedUser> activeRoomUsers = rooms.get(roomName);

                    // 2. 방이 메모리에 없으면(서버 재시작 등) 새로 생성하여 복구
                    if (activeRoomUsers == null) {
                        activeRoomUsers = new CopyOnWriteArrayList<>();
                        rooms.put(roomName, activeRoomUsers);
                    }

                    // 3. 내 소켓 객체(this.user)를 실시간 리스트에 주입 (Context Injection)
                    if (!activeRoomUsers.contains(this.user)) {
                        activeRoomUsers.add(this.user);
                    }
                }
            }
            // =========================================================================

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
                    case "CREATE": 					createRoom(json.optString("room")); break;
                    case "INDEX": 					indexRoom(json.optString("room")); break;
                    case "USER_LIST": 				sendUserList(json.optString("room")); break;
                    case "INVITE": 					invite(json.optString("room"), json.optString("user")); break;
                    case "INVITE_AVAILABLE_USERS": 	sendInviteAvailableUsers(json.optString("room")); break;
                    case "EXIT": 					exitRoom(json.optString("room")); break;
                    case "RESTORE_ROOMS": 			restoreMyRooms(); break;
                    case "OFF": 					off(); break;
                    case "CHAT": 
                        String roomName = json.optString("room"); // 클라이언트에서 room 필드도 함께 보내야 함
                        String message = json.optString("message");
                        broadcastToRoom(roomName, message); 
                        break;

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
            if (roomName.trim().isEmpty())		{ sendJsonError("INVALID_NAME", "Room name cannot be blank.", "CREATE"); return; }
            if (rooms.containsKey(roomName)) 	{ sendJsonError("ROOM_EXISTS", "This room already exists.", "CREATE"); return; }

            // 방 생성 및 유저 추가
            List<ConnectedUser> roomUsers = new CopyOnWriteArrayList<>();	//방에 접속한 유저 리스트 저장한 변수 선언
            
            UserAccount account = getMyAccount(); 
            if (account != null) {
                account.addRoom(roomName); 
            }
            
            roomUsers.add(user);		//본인 입장
            
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
        // 방 입장 및 대화 내용 불러오기
        private void indexRoom(String roomName) {
        	
            // 방이 없으면 만들고, 나를 방 멤버로 등록
            joinProcess(roomName);

            // 저장된 채팅 내역을 JSON 배열로 가져온다
            JSONArray historyArray = getChatHistoryJson(roomName);

            // 클라이언트에게 전송
            JSONObject sendJson = new JSONObject();
            sendJson.put("status", "OK");
            sendJson.put("command", "INDEX");
            sendJson.put("room", roomName);
            sendJson.put("history", historyArray);

            sendJsonToUser(user, sendJson);
        }

        // 보조함수===========================================
        //  방 입장 처리 로직 (방 생성 + 접속자 명단 추가 + 내 계정에 저장)
        private void joinProcess(String roomName) {
        	
            // 방 리스트가 없으면 생성 
            rooms.putIfAbsent(roomName, new CopyOnWriteArrayList<>());
            
            List<ConnectedUser> roomUsers = rooms.get(roomName);
            
            // 접속자 명단에 내가 없으면 추가
            if (!roomUsers.contains(user)) {
                roomUsers.add(user);
                
                // 내 영구 계정(UserAccount)에도 방 추가
                UserAccount account = getMyAccount(); 
                if (account != null) {
                    account.addRoom(roomName); 
                }
            }
        }
        
        // 보조함수===========================================
        //  채팅 내역을 JSON 배열로 변환하는 로직
        private JSONArray getChatHistoryJson(String roomName) {
        	
            // 내역 가져오기 (없으면 빈 리스트)
            List<String> chatHistory = roomMessages.getOrDefault(roomName, new ArrayList<>());
            JSONArray historyArray = new JSONArray();

            for (String pastMsg : chatHistory) {
                try {
                    // 문자열을 JSON 객체로 변환해서 담기
                    JSONObject msgJson = new JSONObject(pastMsg);
                    historyArray.put(msgJson);
                    
                } catch (Exception e) {
                    // 변환 실패 = 문자열,  그대로 담기
                    historyArray.put(pastMsg);
                }
            }
            return historyArray;
        }
        //===========================================
        
        // ===== 방 유저 수, 이름 목록 전송 =====
        // [현재 방 참여자 목록 전송]
        private void sendUserList(String roomName) {
            JSONObject json = new JSONObject();
            json.put("command", "USER_LIST");
            json.put("room", roomName);

            JSONArray usersArr = new JSONArray();
            int totalCount = 0;
            
            //  전체 계정(accounts)을 전수 조사하여 오프라인 멤버도 포함
            for (UserAccount account : accounts.values()) {
                // 계정 정보에 "이 방에있으면 카운트
                if (account.isInRoom(roomName)) {
                    usersArr.put(account.getName()); // 접속 여부 상관없이 추가
                    totalCount++;
                }
            }
         

            // 결과 전송
            json.put("users", usersArr);
            json.put("userCount", totalCount); // 배열 길이로 자동 계산

            sendJson(json); 
        }
        
        // ===== 방 초대 =====
        private void invite(String roomName, String targetUserId) {  
            
            // 1. 기본 검증 (나, 방 존재 여부 등)
            UserAccount myAccount = getMyAccount(); 
            if (myAccount == null || !myAccount.isInRoom(roomName)) {
                sendJsonError("NOT_IN_ROOM", "You are not in this room.", "INVITE");
                return;
            }

            if (targetUserId.equals(user.getUserId())) { // 내 ID와 비교
                sendJsonError("SELF_INVITE", "Cannot invite yourself.", "INVITE");
                return;
            }

            // 2. 계정(Account) 찾기 및 방 추가 (영구 저장)
            UserAccount invitedAccount = accounts.get(targetUserId);
            
            if (invitedAccount == null) {
                UserDAO dao = new UserDAO();
                invitedAccount = dao.findUserById(targetUserId);
                
                // DB에서 찾았으면 메모리에 임시로 올려둡니다. (그래야 방 목록을 저장하니까요)
                if (invitedAccount != null) {
                    accounts.put(targetUserId, invitedAccount);
                }
            }

        	// DB에도 없으면 진짜 없는 유저입니다.
            if (invitedAccount == null) {
                sendJsonError("USER_NOT_FOUND", "User does not exist.", "INVITE");
                return;
            }
            
            if (invitedAccount.isInRoom(roomName)) {
                sendJsonError("ALREADY_IN_ROOM", "User already in room.", "INVITE");
                return;
            }
            
            invitedAccount.addRoom(roomName); // 계정에 저장 (재접속 시 유지용)


            // =================================================================
            //  실시간 접속자 찾기 로직 (ID로 비교)
            // =================================================================
            ConnectedUser invitedConnectedUser = connectedUsers.stream()
                    .filter(u -> {
                        // u.getUserId()가 있으면 그걸로 비교하고, 없으면 이름으로라도 비교 시도
                        String uID = u.getUserId();     // ConnectedUser에 이 메서드가 있어야 함!
                        String uName = u.getUserName();
                        return targetUserId.equals(uID) || targetUserId.equals(uName);
                    })
                    .findFirst()
                    .orElse(null);


            // 접속 중이라면 '실시간 처리'를 반드시 해줘야 함
            if (invitedConnectedUser != null) {
                
                // A. 배너 알림 전송 (즉시 배너 생성됨)
                JSONObject jsonInvite = new JSONObject();
                jsonInvite.put("status", "INFO");
                jsonInvite.put("command", "INVITE");
                jsonInvite.put("message", user.getUserName() + " invited you.");
                jsonInvite.put("username", user.getUserName());
                jsonInvite.put("room", roomName);
                
                sendJsonToUser(invitedConnectedUser, jsonInvite);

                // 초대된 사람의 소켓을 '현재 방의 활성 멤버 리스트'에 즉시 추가
                List<ConnectedUser> currentRoomUsers = rooms.get(roomName);
                
                // 방이 메모리에 없으면 생성 (혹시 모를 에러 방지)
                if (currentRoomUsers == null) {
                    currentRoomUsers = new CopyOnWriteArrayList<>();
                    rooms.put(roomName, currentRoomUsers);
                }

                // 리스트에 없으면 추가 (이제 이 사람한테도 채팅이 전송됨)
                if (!currentRoomUsers.contains(invitedConnectedUser)) {
                    currentRoomUsers.add(invitedConnectedUser);
                    System.out.println("[System] " + targetUserId + " added to active room list: " + roomName);
                }
            }

            // 3. 방 전체에 알림 메시지 (로그용)
            JSONObject notice = new JSONObject();
            notice.put("command", "CHAT");
            notice.put("status", "INFO");
            notice.put("room", roomName);
            
            String targetName = invitedAccount.getName(); 
            notice.put("message", user.getUserName() + " invited " + targetName);
            
            if (roomMessages.containsKey(roomName)) {
                roomMessages.get(roomName).add(notice.toString());
            }
            
            sendJsonToRoom(roomName, notice);
        }
        
        // ===== 초대 가능한 유저 리스트 반환 (전체 가입자 기준) =====
        private void sendInviteAvailableUsers(String roomName) {
            JSONObject json = new JSONObject();
            json.put("command", "INVITE_AVAILABLE_USERS");
            json.put("room", roomName);

            // 현재 방에 있는 사람들의 이름을 명단 생성
            List<ConnectedUser> currentMembers = rooms.get(roomName);
            Set<String> alreadyInRoom = new HashSet<>();
            
            if (currentMembers != null) {
                for (ConnectedUser u : currentMembers) {
                    alreadyInRoom.add(u.getUserName());
                }
            }

            JSONArray arr = new JSONArray();

            // '접속자(connectedUsers)'가 아니라 '전체 가입자(accounts)'를 순회
            for (UserAccount account : accounts.values()) {
                String targetName = account.getName();
                String targetId   = account.getId();

                // 나 자신은 초대 목록에서 제거
                if (targetName.equals(user.getUserName())) {
                    continue;
                }

                // 이미 방에 들어와 있는 사람제거
                if (alreadyInRoom.contains(targetName)) {
                    continue;
                }

                // 초대 가능한 사람만 추가
                JSONObject userObj = new JSONObject();
                userObj.put("id", targetId);     
                userObj.put("name", targetName); 
                
                arr.put(userObj);
            }

            json.put("users", arr);
            json.put("userCount", arr.length());

            // 요청한 사용자에게만 전송
            sendJson(json);
        }



        // ===== 방 나가기 =====
        private void exitRoom(String roomName) {
        	UserAccount account = getMyAccount();

            // 내가 이 방에 진짜 들어있는지 확인 (account 기준)
            if (account == null || !account.isInRoom(roomName)) {
                sendJsonError("NOT_IN_ROOM", "You are not in this room.", "EXIT");
                return;
            }

            List<ConnectedUser> roomUsers = rooms.get(roomName);	// 전체방중 특정방에 유저 리스트를 저장
            if (roomUsers != null) {		//빈 방이 아니라면
                roomUsers.remove(user);		//유저 리스트에서 본인을 제거
                account.removeRoom(roomName);	//본인 데이터에도 방 이름 제거

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
        //=======재접속시 있던방 요청=========
        private void restoreMyRooms() {
            
            // 영구 저장된 계정 정보 가져오기
            UserAccount savedAccount = getMyAccount();
            
            // 저장된 계정 정보가 없으면 로직 종료
            if (savedAccount == null) return; 

            // 저장된 계정 객체에서 방 목록을 가져옴 (여기에 과거의 방들이 들어있어야 함)
            Set<String> myRooms = savedAccount.getJoinedRooms(); 

            // 응답 JSON 생성
            JSONObject json = new JSONObject();
            json.put("command", "RESTORE_ROOMS");
            
            JSONArray roomsArray = new JSONArray();

            if (myRooms != null && !myRooms.isEmpty()) {
                for (String roomName : myRooms) {
                    
                    //  rooms 맵을 이용해 방이 실제로 존재하는지 더블 체크
                    if (!rooms.containsKey(roomName)) {
                        continue; // 서버 메모리에서 방이 삭제되었다면 건너뜀
                    }

                    JSONObject roomData = new JSONObject();
                    roomData.put("roomName", roomName);

                    // roomMessages 맵을 이용해 마지막 대화 내용 가져오기
                    String lastMsg = "";
                    if (roomMessages.containsKey(roomName)) {
                        List<String> msgs = roomMessages.get(roomName);
                        if (msgs != null && !msgs.isEmpty()) {
                            try {
                                String lastJsonStr = msgs.get(msgs.size() - 1);
                                JSONObject lastJsonObj = new JSONObject(lastJsonStr);
                                lastMsg = lastJsonObj.optString("message", "");
                            } catch (Exception e) {
                                lastMsg = "past conversations";
                            }
                        }
                    }
                    roomData.put("lastMsg", lastMsg);
                    roomsArray.put(roomData);
                }
            }

            json.put("rooms", roomsArray);
            
            // 4. 전송 (빈 배열이라도 전송됨)
            sendJson(json);
        }
        
        // ===== 종료 =====
        private void off() {
            // 접속자 목록(connectedUsers)에서 제거
            connectedUsers.remove(user); 

            // 소켓 연결만 끊음 (데이터는 메모리에 남겨둠)
            try {
                if (socket != null) socket.close();
                if (in != null) 	in.close();
                if (out != null) 	out.close();
            } catch (IOException e) { e.printStackTrace(); }

            if (user != null) System.out.println(user.getUserName() + " Connection terminated");
        }
        
        // ===== 메시지 브로드캐스트 =====
        private void broadcastToRoom(String roomName, String chatMessage) {
        	UserAccount account = getMyAccount();
            
            if (account == null || !account.isInRoom(roomName)) {    
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

        //개별적인 메시지 응답 
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


    }
}

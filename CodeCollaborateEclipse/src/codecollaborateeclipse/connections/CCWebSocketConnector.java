package codecollaborateeclipse.connections;

import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import codecollaborateeclipse.Core;
import codecollaborateeclipse.Storage;
import codecollaborateeclipse.document.DocumentManager;
import codecollaborateeclipse.models.FileChangeRequest;
import codecollaborateeclipse.models.LoginRequest;
import codecollaborateeclipse.models.Notification;
import codecollaborateeclipse.models.PullFileRequest;
import codecollaborateeclipse.models.Request;
import codecollaborateeclipse.models.Response;
import codecollaborateeclipse.models.Response.PatchData;
import codecollaborateeclipse.models.SubscribeRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * Created by fahslaj on 10/10/2015.
 */
public class CCWebSocketConnector {

    private Queue requestQueue;
    private HashMap<Integer, Request> requestMap = new HashMap();
    private ObjectMapper mapper = new ObjectMapper();
    private DocumentManager listener;
    private int currentTag = 0;

    WebSocketClient client;
    CCWebSocket socket;
    URI uri;
    
    public void setEditorListener(DocumentManager listener) {
    	this.listener = listener;
    }
    
    public boolean sendPatch() {
    	return sendPatch ("@@ -40,16 +40,17 @@\\n almost i\\n+t\\n n shape");
    }
    
    public boolean sendPatch(String patch) {
    	String ResId = "5629a0c2111aeb63cf000002";
    	long FileVersion = System.currentTimeMillis();
    	String Changes = patch;//.replaceAll("\n", "");
    	
        FileChangeRequest fcr = new FileChangeRequest(getTag());
        fcr.setResId(ResId);
        fcr.setFileVersion(FileVersion);
        fcr.setChanges(Changes);
        fcr.setUsername(Storage.getUsername());
        fcr.setToken(Storage.getToken());
        requestMap.put(fcr.getTag(), fcr);
    	try {
            socket.sendMessage(mapper.writeValueAsString(fcr));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean login() {
        LoginRequest lr = new LoginRequest(getTag());
        lr.setUsername(Storage.getUsername());
        lr.setPassword(Storage.getPassword());
        requestMap.put(lr.getTag(),  lr);
    	try {
            socket.sendMessage(mapper.writeValueAsString(lr));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean subscribe() {
        String[] projects = {"5629a063111aeb63cf000001"};
        SubscribeRequest sr = new SubscribeRequest(getTag());
        sr.setUsername(Storage.getUsername());
        sr.setToken(Storage.getToken());
        sr.setProjects(projects);
        requestMap.put(sr.getTag(), sr);
        try {
            socket.sendMessage(mapper.writeValueAsString(sr));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public boolean connect() {
        return connect("ws://codecollaborate.csse.rose-hulman.edu/ws/");
    }

    public boolean connect(String uriString) {
    	if (client != null || socket != null) {
	        return false;
    	}
        client = new WebSocketClient();
        socket = new CCWebSocket(this);
        try {
            client.start();
            uri = new URI(uriString);
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            client.connect(socket, uri, request);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    public boolean close() {
    	if (client == null || socket == null) {
    		return false;
    	}
        boolean closeStatus = false;
        try {
            closeStatus = socket.awaitClose(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            try {
                client.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        client = null;
        socket = null;
        return closeStatus;
    }
    
    /**
     * Called when a response or notification is received from the server
     * @param jsonResponse
     */
    public void receiveMessage(String jsonMessage) {
    	JSONObject jobject = new JSONObject(jsonMessage);
    	//String type = jobject.getString("Type");
    	//if (type.equals("Response")) {
    	if (jobject.has("Tag")) {
    		// is Response
    		int tag = jobject.getInt("Tag");
    		if (requestMap.containsKey(tag)) {
    			System.out.println("Got response for tag: "+tag);
    			Response response = null;
    			try {
					response = mapper.readValue(jsonMessage, Response.class);
				} catch (Exception e) {
					e.printStackTrace();
				}
    			interpretResponse(response, requestMap.remove(tag));
    		}
    	} else {
    		// is Notification
        	JSONObject data = (JSONObject) jobject.get("Data");
        	if (data.has("Changes"))
        		listener.recievePatch(data.getString("Changes"));
    	}
    }
    
    public boolean pullDocument() {
    	return pullDocument("5629a0c2111aeb63cf000002");
    }
    
    public boolean pullDocument(String resId) {
        PullFileRequest pfr = new PullFileRequest(getTag());
        pfr.setResId(resId);
        pfr.setUsername(Storage.getUsername());
        pfr.setToken(Storage.getToken());
        requestMap.put(pfr.getTag(), pfr);
    	try {
            socket.sendMessage(mapper.writeValueAsString(pfr));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
    
    private int getTag() {
    	return currentTag++;
    }
    
    private void interpretResponse(Response response, Request request) {
    	if (response == null) {
    		System.out.println("Failed to interpret response.");
    		return;
    	}
    	if (request instanceof LoginRequest && response.getData() != null) {
    		System.out.println("Retrieving user details...");
    		Storage.setToken(response.getData().getToken());
    		System.out.println("Token: "+Storage.getToken());
    	} else if (request instanceof PullFileRequest && response.getData() != null) {
    		System.out.println("Retrieving file data...");
    		PatchData[] changes = response.getData().getChanges();
    		System.out.println("Got changes from storage: "+changes);
    		if (changes != null) {
	    		for (int i = 0; i < changes.length; i++) {
	    			listener.recievePatch(changes[i].getChanges());
	    		}
    		}
    	}
    	switch (response.getStatus()) {
    		case 1: return; 
    		case -100: //no such user found error
    		case -101: //error creating user: internal error
    		case -102: //error creating user: duplicate username (reprompt for new username)
    		case -103: //error logging in: internal error
    		case -104: //error logging in: Invalid Username or Password
    		case -105: break;//listener.repromptLogin(); Error logging in: Invalid Token
    		
    		case -200: //no such project found
    		case -201: //error creating project: internal error
    		case -202: //error renaming project: internal error
    		case -203: //error granting permissions: internal error
    		case -204: //error revoking permissions: internal error
    		case -205: //error revoking permissions: must have an owner
    		case -206: break; //error subscribing to project
    		
    		case -300: //no such file found
    		case -301: //error creating file: internal error
    		case -302: //error renaming file: internal error
    		case -303: //error moving file: internal error
    		case -304: //error deleting file: internal error
    		case -305: //error creating file: duplicate file
    		case -306: //error renaming file: duplicate file
    		case -307: //error moving file: duplicate file
    		case -308: break; //error creating file: invalid file path
    			
    		case -400: //error inserting change: internal error
    		case -401: //error inserting change: duplicate version number
    		case -402: //error reading change: internal error
    		case -420: break;//error, too blazed
    	}
    	
    	System.out.println("Successfully interpreted response status: "+response.getStatus());
    }
}

package TCP;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FTPServer{
	public static void main(String[] args) throws Exception{
		String clientSentence=null;
		String currentDirectory="./";
		int controlPort=2020;
		int dataPort=2121;
		
		if(args.length >=2) {
			controlPort = Integer.parseInt(args[0]);
			dataPort = Integer.parseInt(args[1]);
		}
		ServerSocket welcomeSocket = new ServerSocket(controlPort);		

			Socket connectionSocket = welcomeSocket.accept();
			
			BufferedReader inFromClient =
					new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
			
			DataOutputStream outToClient = new DataOutputStream(connectionSocket.getOutputStream());
		while(true) {
			String sendString="";
			clientSentence = inFromClient.readLine();
			
			String[] commend =clientSentence.split("(?i)\\s"); 
			String route=null;
			if(commend.length==2)
				route = commend[1];
	
			if(commend[0].equalsIgnoreCase("QUIT")) {
				inFromClient.close();
				outToClient.close();
				connectionSocket.close();
				break;
			}
			else if(commend[0].equalsIgnoreCase("CD")) { 
				File f=null;
				if(route !=null) { 
					if(Paths.get(route).isAbsolute()){
						f = new File(route).getCanonicalFile();
						if(f.exists() && f.isDirectory())
							currentDirectory=route;
					}
					else {			
						f = new File(currentDirectory+"/"+route).getCanonicalFile();
						if(f.exists() && f.isDirectory())
							currentDirectory+="/"+route;
					}			
				}
				else f = new File(currentDirectory).getCanonicalFile();
				
				if(!f.exists() || !f.isDirectory() ) 
					sendString += "501 Failed : directory name is invalid";	
				else
					sendString += "200 Moved to " + f.getPath();
				
				
				// server console message
				System.out.println("Request:" + clientSentence);
				System.out.println("Response: "+sendString);
				
				outToClient.writeBytes(sendString +"\n");
			}
			else if(commend[0].equalsIgnoreCase("LIST")) {
				File f=null;
				File[] lists=null;
				if(route !=null) { 
					if(Paths.get(route).isAbsolute()) 
						f =new File(route);
					else 					
						f =new File(currentDirectory+"/"+route).getCanonicalFile();
				}
				else 
					f = new File(currentDirectory);
				
				if(!f.exists() || !f.isDirectory()) { 
					sendString = "501 Failed : Directory name is invalid";
					outToClient.writeBytes(sendString+"\n");
				}
				else { 
					lists = f.listFiles();
					sendString ="200 Comprising " + lists.length + " entries";
					outToClient.writeBytes(sendString+"\n");
					
					sendString="";
					
					for(int i=0;i<lists.length;i++) {
						if(lists[i].isDirectory()) {
							sendString += lists[i].getName()+",-"+",";
						}
						else
							sendString +=lists[i].getName()+','+lists[i].length()+",";
					}
					outToClient.writeBytes(sendString+"\n");
				}
				
				
				// server console message
				System.out.println("Request:" + clientSentence);
				if(!f.exists() || !f.isDirectory())
					System.out.println("Response: 501 Failed : Directory name is invalid");
				else
					System.out.println("Response: 200 Comprising " + lists.length + " entries");
				
			}
			else if(commend[0].equalsIgnoreCase("GET")) {
				File f= null;
				if(Paths.get(route).isAbsolute()) 
					f = new File(route);
				else
					f= new File(currentDirectory+"/"+route).getCanonicalFile();
				
				if(!f.isFile() || !f.exists()) {
					sendString = "401 Failed : No such file exists";
					outToClient.writeBytes(sendString +"\n");
				}
				else {
					sendString = "200 Containing "+ f.length() +" bytes in total";
					outToClient.writeBytes(sendString +"\n");
					
					ServerSocket welcomeSocketFile = new ServerSocket(dataPort);		
					Socket connectionSocketFile = welcomeSocketFile.accept();
					
					FileInputStream input = new FileInputStream(f);
					InputStream inACK = connectionSocketFile.getInputStream();
					OutputStream output = connectionSocketFile.getOutputStream();
					byte[] chunk = new byte[1005];
					byte[] getACK = new byte[4];
					chunk[0] = 1;
					chunk[1] = 0x00;
					chunk[2] = 0x00;
					if(input.available()>=1000) {
						chunk[3]=(byte)(1000>>8);
						chunk[4]=(byte)1000;
					}
					else {
						chunk[3]=(byte)(input.available()>>8);
						chunk[4]=(byte)input.available();
					}
					int read;
					read = input.read(chunk,5,1000);
					while(read>0) {
						//System.out.println(Integer.parseInt(Integer.toBinaryString(Byte.toUnsignedInt(chunk[3]))+Integer.toBinaryString(Byte.toUnsignedInt(chunk[4])),2));
						output.write(chunk,0,read+5);
						inACK.read(getACK);
						if(input.available()>=1000) {
							chunk[3]=(byte)(1000>>8);
							chunk[4]=(byte)1000;
						}
						else {
							chunk[3]=(byte)(input.available()>>8);
							chunk[4]=(byte)input.available();
						}
						read = input.read(chunk,5,1000);
						chunk[0] = getACK[0];
					}
						
					connectionSocketFile.close();
					welcomeSocketFile.close();
					output.close();
					input.close();
					inACK.close();
								
				}
				// server console message
				System.out.println("Requst: "+clientSentence);
				System.out.println("Response: "+sendString);
				
			}
			else if(commend[0].equalsIgnoreCase("PUT")) {
				File f= null;
				String fileName = route;
				f = new File(currentDirectory+"/"+route).getCanonicalFile();
				int fileSize = inFromClient.read();
				
				if(route == null || fileSize==0) {
					sendString ="501 Failed for unknown reason";
					outToClient.writeBytes(sendString+"\n");
				}
				else {
					sendString ="200 Ready to receive";
					outToClient.writeBytes(sendString+"\n");
					
					ServerSocket welcomeSocketFile = new ServerSocket(dataPort);		
					Socket connectionSocketFile = welcomeSocketFile.accept();
					
					FileOutputStream output = new FileOutputStream(f);
					InputStream input = connectionSocketFile.getInputStream();
					OutputStream outACK = connectionSocketFile.getOutputStream();
				

					byte[] chunk = new byte[1005];
					byte[] ACK = new byte[3];
					int read;
					ACK[1] = ACK[2] = 0x00;
				
					read = input.read(chunk);
					while(read>0) {
						ACK[0] = 1;
						ACK[0] +=chunk[0];
						outACK.write(ACK);
						output.write(chunk,5,read-5);
						read = input.read(chunk);
					}
					output.close();
					input.close();
					welcomeSocketFile.close();
					connectionSocketFile.close();
				}
				
				// server console message
				System.out.println("Request: "+fileName);
				System.out.println("Request: "+fileSize);
				System.out.println("Response: "+ sendString);
	
			}

			System.out.println();
			outToClient.flush();
		}
	}
}


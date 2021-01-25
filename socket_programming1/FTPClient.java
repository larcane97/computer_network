package TCP;


import java.io.*;
import java.net.*;
import java.util.*;

import javax.sound.sampled.Port;

public class FTPClient {
	
	public static void main(String args[]) throws Exception{
		String sentence;
		String sentSentence;
		String serverIP="127.0.0.1";
		int controlPort=2020;
		int dataPort=2121;
		
		if(args.length >=3) {
			serverIP = args[0];
			controlPort = Integer.parseInt(args[1]);
			dataPort = Integer.parseInt(args[2]);
		}
		
		BufferedReader inFromUser =
				new BufferedReader(new InputStreamReader(System.in));
		
		Socket clientSocket = new Socket(serverIP,controlPort);
		System.out.println("###Success connection###");
		
		DataOutputStream outToServer =
				new DataOutputStream(clientSocket.getOutputStream());
		
		BufferedReader inFromServer =
				new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		
		while(true) {
			sentence = inFromUser.readLine();
			outToServer.writeBytes(sentence +'\n');
			
			String[] commend = sentence.split("(?i)\\s");
			String fileName = null;
			
			
			
			if(commend[0].equalsIgnoreCase("QUIT")) {
				clientSocket.close();
				inFromUser.close();
				outToServer.close();
				inFromServer.close();
				break;
			}
			else if(commend[0].equalsIgnoreCase("CD")) {
				sentSentence = inFromServer.readLine();
				int statusCode = Integer.parseInt(sentSentence.substring(0,3));
				if(statusCode==200)
					System.out.println(sentSentence.substring(sentSentence.indexOf("C:\\")));
				else // statusCode==501
					System.out.println("Failed – directory name is invalid");
			}
			else if(commend[0].equalsIgnoreCase("LIST")) {
				sentSentence = inFromServer.readLine();
				int statusCode = Integer.parseInt(sentSentence.substring(0,3));
					if(statusCode==200) {
					sentSentence = inFromServer.readLine();
					StringTokenizer st = new StringTokenizer(sentSentence,",");
					while(st.hasMoreTokens()) {
						System.out.println(st.nextToken()+','+st.nextToken());
						}
					}
					else // statusCode==501
						System.out.println("Failed – directory name is invalid");
			}
			else if(commend[0].equalsIgnoreCase("GET")) {
				sentSentence = inFromServer.readLine();

				int statusCode = Integer.parseInt(sentSentence.substring(0,3));
				if(statusCode==200) {
					Socket dataChannelSocket = new Socket(serverIP,dataPort);
					System.out.println("###Open Data Link###");
					
					int fileNameIdx;
					if( (fileNameIdx = commend[1].lastIndexOf("/")) == -1)
							fileNameIdx=0;
					else fileNameIdx++;
					fileName = commend[1].substring(fileNameIdx);
					String fileSize = sentSentence.substring(15,sentSentence.indexOf("bytes")-1);
					System.out.println("Received " + fileName +"/ "+fileSize +"bytes");
					
					InputStream input = dataChannelSocket.getInputStream();
					OutputStream outACK = dataChannelSocket.getOutputStream();
					FileOutputStream output = new FileOutputStream("./"+fileName);
					
					byte[] chunk = new byte[1005];
					byte[] ACK = new byte[3];
					int read;
					ACK[1] = ACK[2] =0x00;
					
					read = input.read(chunk);
					while(read>0) {
						ACK[0] = 1;
						ACK[0] += chunk[0];
						outACK.write(ACK);
						output.write(chunk,5,read-5);
						System.out.print("# ");
						read = input.read(chunk);
					}

					System.out.println("  Completed...");
					
					output.close();
					input.close();
					outACK.close();
					dataChannelSocket.close();	
				}
				else // statusCode==401
					System.out.println("Failed – Such file does not exist!");
				
			
			}
			else if(commend[0].equalsIgnoreCase("PUT")) {

				fileName = commend[1];
				File f = new File("./"+fileName).getCanonicalFile();
				int fileSize;
				if(f.exists() && f.isFile())
					fileSize= (int)f.length();
				else
					fileSize=0;
				outToServer.write(fileSize);
				
				sentSentence = inFromServer.readLine();
				int statusCode = Integer.parseInt(sentSentence.substring(0,3));
	
				if(statusCode==200) {
					Socket dataChannelSocket = new Socket(serverIP,dataPort);
					System.out.println("###Open Data Link###");
					
					FileInputStream input= new FileInputStream(f);
					InputStream inACK = dataChannelSocket.getInputStream();
					OutputStream output = dataChannelSocket.getOutputStream();
			
					System.out.println(fileName+" transferred  / " +fileSize+" bytes");
					byte[] chunk = new byte[1005];
					byte[] getACK = new byte[3];
					chunk[0]=1;
					chunk[1] =0x00;
					chunk[2] =0x00;
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
						output.write(chunk,0,read+5);
						System.out.print("# ");
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
					System.out.println("  Completed...");
					
					input.close();
					output.close();
					dataChannelSocket.close();
					inACK.close();
				}
				else
					System.out.println("Failed for unknown reason");
					
			}
			
			outToServer.flush();
		}			 
	}
}



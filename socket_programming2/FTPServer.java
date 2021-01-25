package TCP_SR;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

public class FTPServer {

	static class TimerTask_ extends TimerTask {
		byte[] chunk;
		OutputStream output;
		int Read;
		Timer timer;

		public TimerTask_(byte[] chunk, int Read, OutputStream output) {
			this.chunk = chunk;
			this.output = output;
			this.Read = Read;
		}

		@Override
		public void run() {
			System.out.printf("resend pkt%d\n", (int) chunk[0]);
			try {
				output.write(chunk, 0, Read + 5);
			} catch (IOException e) {
				// NOTHING..
			}
		}
	}

	public static void main(String[] args) throws Exception {
		String clientSentence = null;
		String currentDirectory = "./";
		int controlPort = 2020;
		int dataPort = 2121;

		if (args.length >= 2) {
			controlPort = Integer.parseInt(args[0]);
			dataPort = Integer.parseInt(args[1]);
		}
		ServerSocket welcomeSocket = new ServerSocket(controlPort);

		Socket connectionSocket = welcomeSocket.accept();

		BufferedReader inFromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));

		DataOutputStream outToClient = new DataOutputStream(connectionSocket.getOutputStream());

		boolean DROP = false, TIMEOUT = false, BITERROR = false;
		int errorChkNo = -1;

		label: while (true) {
			String sendString = "";
			clientSentence = inFromClient.readLine();

			String[] commend = clientSentence.split("(?i)\\s");
			String route = null;
			if (commend.length == 2)
				route = commend[1];

			if (commend[0].equalsIgnoreCase("QUIT")) {
				welcomeSocket.close();
				inFromClient.close();
				outToClient.close();
				connectionSocket.close();
				break label;
			} else if (commend[0].equalsIgnoreCase("DROP")) {
				System.out.println("request :" + clientSentence);
				System.out.println("response : PACKET DROP SET");
				DROP = true;
				errorChkNo = Integer.parseInt(commend[1]);
			} else if (commend[0].equalsIgnoreCase("TIMEOUT")) {
				System.out.println("request :" + clientSentence);
				System.out.println("response : PACKET TIMEOUT SET");
				TIMEOUT = true;
				errorChkNo = Integer.parseInt(commend[1]);
			} else if (commend[0].equalsIgnoreCase("BITERROR")) {
				System.out.println("request :" + clientSentence);
				System.out.println("response : PACKET BITERROR SET");
				BITERROR = true;
				errorChkNo = Integer.parseInt(commend[1]);
			} else if (commend[0].equalsIgnoreCase("CD")) {
				File f = null;
				if (route != null) {
					if (Paths.get(route).isAbsolute()) {
						f = new File(route).getCanonicalFile();
						if (f.exists() && f.isDirectory())
							currentDirectory = route;
					} else {
						f = new File(currentDirectory + "/" + route).getCanonicalFile();
						if (f.exists() && f.isDirectory())
							currentDirectory += "/" + route;
					}
				} else
					f = new File(currentDirectory).getCanonicalFile();

				if (!f.exists() || !f.isDirectory())
					sendString += "501 Failed : directory name is invalid";
				else
					sendString += "200 Moved to " + f.getPath();

				// server console message
				System.out.println("Request:" + clientSentence);
				System.out.println("Response: " + sendString);

				outToClient.writeBytes(sendString + "\n");
			} else if (commend[0].equalsIgnoreCase("LIST")) {
				File f = null;
				File[] lists = null;
				if (route != null) {
					if (Paths.get(route).isAbsolute())
						f = new File(route);
					else
						f = new File(currentDirectory + "/" + route).getCanonicalFile();
				} else
					f = new File(currentDirectory);

				if (!f.exists() || !f.isDirectory()) {
					sendString = "501 Failed : Directory name is invalid";
					outToClient.writeBytes(sendString + "\n");
				} else {
					lists = f.listFiles();
					sendString = "200 Comprising " + lists.length + " entries";
					outToClient.writeBytes(sendString + "\n");

					sendString = "";

					for (int i = 0; i < lists.length; i++) {
						if (lists[i].isDirectory()) {
							sendString += lists[i].getName() + ",-" + ",";
						} else
							sendString += lists[i].getName() + ',' + lists[i].length() + ",";
					}
					outToClient.writeBytes(sendString + "\n");
				}

				// server console message
				System.out.println("Request:" + clientSentence);
				if (!f.exists() || !f.isDirectory())
					System.out.println("Response: 501 Failed : Directory name is invalid");
				else
					System.out.println("Response: 200 Comprising " + lists.length + " entries");

			} else if (commend[0].equalsIgnoreCase("GET")) {
				File f = null;
				if (Paths.get(route).isAbsolute())
					f = new File(route);
				else
					f = new File(currentDirectory + "/" + route).getCanonicalFile();

				if (!f.isFile() || !f.exists()) {
					sendString = "401 Failed : No such file exists";
					outToClient.writeBytes(sendString + "\n");
				} else {
					sendString = "200 Containing " + f.length() + " bytes in total";
					outToClient.writeBytes(sendString + "\n");

					ServerSocket welcomeSocketFile = new ServerSocket(dataPort);
					Socket connectionSocketFile = welcomeSocketFile.accept();

					FileInputStream input = new FileInputStream(f);
					InputStream inACK = connectionSocketFile.getInputStream();
					OutputStream output = connectionSocketFile.getOutputStream();

					int windowSize = 5;
					int currentPoint = 0;
					int nextPoint = 0;
					int seqNum = 16;
					Timer[] timer = new Timer[16];
					TimerTask_[] task = new TimerTask_[16];

					byte[] chunk = new byte[1005];
					byte[] getACK = new byte[3];
					chunk[0] = -1;
					int read = -1;
					Timer timeoutTimer = new Timer();
					TimerTask_ timeoutTask = null;
					boolean[] ACKed = new boolean[seqNum];
					Arrays.fill(ACKed, Boolean.FALSE);

					while (nextPoint - currentPoint < windowSize) {
						chunk[1] = 0x00;
						chunk[2] = 0x00;
						if (input.available() >= 1000) {
							chunk[3] = (byte) (1000 >> 8);
							chunk[4] = (byte) 1000;
						} else {
							chunk[3] = (byte) (input.available() >> 8);
							chunk[4] = (byte) input.available();
						}

						chunk[0] = (byte) ((chunk[0] + 1) % seqNum);
						read = input.read(chunk, 5, 1000);
						if (read <= 0)
							break;
						timer[nextPoint] = new Timer();
						task[nextPoint] = new TimerTask_(Arrays.copyOf(chunk, chunk.length), read, output);
						timer[nextPoint].schedule(task[nextPoint], 1000);
						if (nextPoint == errorChkNo) {
							if (DROP) {
								DROP = false;
							} else if (TIMEOUT) {
								timeoutTask = new TimerTask_(Arrays.copyOf(chunk, chunk.length), read, output) {
									@Override
									public void run() {
										try {
											output.write(chunk, 0, Read + 5);
											System.out.printf("(TIMEOUT PKT)send pkt%d\n", (int) chunk[0]);
										} catch (IOException e) {
											// NOHTING..
										}

									}
								};
								timeoutTimer.schedule(timeoutTask, (long) 2000);
								TIMEOUT = false;
							} else if (BITERROR) {
								chunk[1] = (byte) 0xFF;
								chunk[2] = (byte) 0xFF;
								BITERROR = false;
								output.write(chunk, 0, read + 5);
							}
							errorChkNo = -1;
						} else
							output.write(chunk, 0, read + 5);

						nextPoint = (nextPoint + 1) % seqNum;

					}
					while (read > 0) {
						inACK.read(getACK, 0, getACK.length);
						System.out.printf("rcv ack%d\n", (int) getACK[0]);
						boolean ackRangeCheck = (currentPoint + windowSize < seqNum)
								? (getACK[0] >= currentPoint && getACK[0] < currentPoint + windowSize)
								: (getACK[0] >= currentPoint || getACK[0] < (currentPoint + windowSize) % seqNum);
						if (ackRangeCheck) { 
							timer[getACK[0]].cancel();
							ACKed[getACK[0]] = true;
						} else
							continue;
						if ((int) getACK[0] == currentPoint) { 
							for (int i = currentPoint; ACKed[i]; i = (i + 1) % seqNum) {
								ACKed[i] = false;
								currentPoint = (currentPoint + 1) % seqNum;
							}
						}
						if (Math.abs(currentPoint - nextPoint) < windowSize
								|| Math.abs(currentPoint - nextPoint) > seqNum - windowSize) {
							read = input.read(chunk, 5, 1000);
							if (read <= 0)
								break;
							chunk[1] = 0x00;
							chunk[2] = 0x00;
							if (input.available() >= 1000) {
								chunk[3] = (byte) (1000 >> 8);
								chunk[4] = (byte) 1000;
							} else {
								chunk[3] = (byte) (input.available() >> 8);
								chunk[4] = (byte) input.available();
							}
							chunk[0] = (byte) ((chunk[0] + 1) % seqNum);
							timer[nextPoint] = new Timer();
							task[nextPoint] = new TimerTask_(Arrays.copyOf(chunk, chunk.length), read, output);
							timer[nextPoint].schedule(task[nextPoint], 1000);

							if (nextPoint == errorChkNo) {
								if (DROP) {
									DROP = false;
								} else if (TIMEOUT) {
									timeoutTask = new TimerTask_(Arrays.copyOf(chunk, chunk.length), read, output) {
										@Override
										public void run() {
											System.out.printf("(TIMEOUT PKT)send pkt%d\n", (int) chunk[0]);
											try {
												output.write(this.chunk, 0, Read + 5);
											} catch (IOException e) {
												// NOHTING
											}
										}
									};
									timeoutTimer.schedule(timeoutTask, 2000);
									TIMEOUT = false;
								} else if (BITERROR) {
									chunk[1] = (byte) 0xFF;
									chunk[2] = (byte) 0xFF;
									BITERROR = false;
									output.write(chunk, 0, read + 5);
								}
								errorChkNo = -1;
							} else
								output.write(chunk, 0, read + 5);

							nextPoint = (nextPoint + 1) % seqNum;
						}
					}
					connectionSocketFile.close();
					welcomeSocketFile.close();
					output.flush();
					output.close();
					input.close();
					inACK.close();

					TIMEOUT = DROP = BITERROR = false;
					for (int i = 0; timer[i] != null && i < timer.length; i = (i + 1) % seqNum) {
						timer[i].cancel();
						timer[i] = null;
					}
					timeoutTimer.cancel();

				}
				// server console message
				System.out.println("\nRequst: " + clientSentence);
				System.out.println("Response: " + sendString);

			} else if (commend[0].equalsIgnoreCase("PUT")) {
				File f = null;
				String fileName = route;
				f = new File(currentDirectory + "/" + route).getCanonicalFile();
				int fileSize = inFromClient.read();

				if (route == null || fileSize == 0) {
					sendString = "501 Failed for unknown reason";
					outToClient.writeBytes(sendString + "\n");
				} else {
					sendString = "200 Ready to receive";
					outToClient.writeBytes(sendString + "\n");

					ServerSocket welcomeSocketFile = new ServerSocket(dataPort);
					Socket connectionSocketFile = welcomeSocketFile.accept();

					FileOutputStream output = new FileOutputStream(f);
					InputStream input = connectionSocketFile.getInputStream();
					OutputStream outACK = connectionSocketFile.getOutputStream();

					byte[] chunk = new byte[1005];
					byte[] ACK = new byte[3];
					int read;
					int expectedNum = 0;
					int windowSize = 5;
					int seqNum = 16;
					byte[][] buffer = new byte[seqNum][];
					for (int i = 0; i < seqNum; i++) {
						buffer[i] = new byte[1005];
						buffer[i] = null;
					}
					ACK[1] = ACK[2] = 0x00;

					while (true) {
						read = input.read(chunk);
						if (read <= 0)
							break;
						if (chunk[1] == (byte) 0xFF && chunk[2] == (byte) 0xFF) {
							System.out.println(chunk[0] + " BIT_ERROR");
							continue;
						}
						ACK[0] = chunk[0];
						System.out.println("seq " + ACK[0]);
						outACK.write(ACK);
						boolean seqRangeCheck = (expectedNum + windowSize < seqNum)
								? (chunk[0] > expectedNum && chunk[0] < expectedNum + windowSize)
								: (chunk[0] > expectedNum || chunk[0] < (expectedNum + windowSize) % seqNum);
						if (expectedNum == chunk[0]) {
							expectedNum = (expectedNum + 1) % seqNum;
							output.write(chunk, 5, read - 5);
							for (int i = expectedNum; buffer[i] != null; i = (i + 1) % seqNum) {
								output.write(buffer[i], 5, read - 5);
								buffer[i] = null;
								expectedNum = (expectedNum + 1) % seqNum;
							}
						} else if (seqRangeCheck)
							buffer[chunk[0]] = Arrays.copyOf(chunk, chunk.length);
					}
					output.close();
					input.close();
					welcomeSocketFile.close();
					connectionSocketFile.close();
				}

				// server console message
				System.out.println("Request: " + fileName);
				System.out.println("Request: " + fileSize);
				System.out.println("Response: " + sendString);
				TIMEOUT = DROP = BITERROR = false;

			}

			System.out.println();
			outToClient.flush();
		}
	}
}

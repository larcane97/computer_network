package TCP_SR;

import java.io.*;
import java.net.*;
import java.util.*;

public class FTPClient {

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

	public static void main(String args[]) throws Exception {
		String sentence;
		String sentSentence;
		String serverIP = "127.0.0.1";
		int controlPort = 2020;
		int dataPort = 2121;

		if (args.length >= 3) {
			serverIP = args[0];
			controlPort = Integer.parseInt(args[1]);
			dataPort = Integer.parseInt(args[2]);
		}

		BufferedReader inFromUser = new BufferedReader(new InputStreamReader(System.in));

		Socket clientSocket = new Socket(serverIP, controlPort);
		System.out.println("###Success connection###");

		DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());

		BufferedReader inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

		boolean DROP = false, TIMEOUT = false, BITERROR = false;
		int errorChkNo = -1;

		while (true) {
			sentence = inFromUser.readLine();
			outToServer.writeBytes(sentence + '\n');

			String[] commend = sentence.split("(?i)\\s");
			String fileName = null;

			if (commend[0].equalsIgnoreCase("QUIT")) {
				clientSocket.close();
				inFromUser.close();
				outToServer.close();
				inFromServer.close();
				break;
			} else if (commend[0].equalsIgnoreCase("DROP")) {
				System.out.println("request :" + sentence);
				System.out.println("response : PACKET DROP SET");
				DROP = true;
				errorChkNo = Integer.parseInt(commend[1]);
			} else if (commend[0].equalsIgnoreCase("TIMEOUT")) {
				System.out.println("request :" + sentence);
				System.out.println("response : PACKET TIMEOUT SET");
				TIMEOUT = true;
				errorChkNo = Integer.parseInt(commend[1]);
			} else if (commend[0].equalsIgnoreCase("BITERROR")) {
				System.out.println("request :" + sentence);
				System.out.println("response : PACKET BITERROR SET");
				BITERROR = true;
				errorChkNo = Integer.parseInt(commend[1]);
			} else if (commend[0].equalsIgnoreCase("CD")) {
				sentSentence = inFromServer.readLine();
				int statusCode = Integer.parseInt(sentSentence.substring(0, 3));
				if (statusCode == 200)
					System.out.println(sentSentence.substring(sentSentence.indexOf("C:\\")));
				else // statusCode==501
					System.out.println("Failed – directory name is invalid");
			} else if (commend[0].equalsIgnoreCase("LIST")) {
				sentSentence = inFromServer.readLine();
				int statusCode = Integer.parseInt(sentSentence.substring(0, 3));
				if (statusCode == 200) {
					sentSentence = inFromServer.readLine();
					StringTokenizer st = new StringTokenizer(sentSentence, ",");
					while (st.hasMoreTokens()) {
						System.out.println(st.nextToken() + ',' + st.nextToken());
					}
				} else // statusCode==501
					System.out.println("Failed – directory name is invalid");
			} else if (commend[0].equalsIgnoreCase("GET")) {
				sentSentence = inFromServer.readLine();

				int statusCode = Integer.parseInt(sentSentence.substring(0, 3));
				if (statusCode == 200) {
					Socket dataChannelSocket = new Socket(serverIP, dataPort);
					System.out.println("###Open Data Link###");

					int fileNameIdx;
					if ((fileNameIdx = commend[1].lastIndexOf("/")) == -1)
						fileNameIdx = 0;
					else
						fileNameIdx++;
					fileName = commend[1].substring(fileNameIdx);
					String fileSize = sentSentence.substring(15, sentSentence.indexOf("bytes") - 1);
					System.out.println("Received " + fileName + "/ " + fileSize + "bytes");

					InputStream input = dataChannelSocket.getInputStream();
					OutputStream outACK = dataChannelSocket.getOutputStream();
					FileOutputStream output = new FileOutputStream("./" + fileName);

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

					System.out.println("Completed...");
					TIMEOUT = DROP = BITERROR = false;
					output.flush();
					outACK.flush();
					output.close();
					input.close();
					outACK.close();
					dataChannelSocket.close();
				} else
					System.out.println("Failed – Such file does not exist!");

			} else if (commend[0].equalsIgnoreCase("PUT")) {

				fileName = commend[1];
				File f = new File("./" + fileName).getCanonicalFile();
				int fileSize;
				if (f.exists() && f.isFile())
					fileSize = (int) f.length();
				else
					fileSize = 0;
				outToServer.write(fileSize);

				sentSentence = inFromServer.readLine();
				int statusCode = Integer.parseInt(sentSentence.substring(0, 3));

				if (statusCode == 200) {
					int windowSize = 5;
					int currentPoint = 0;
					int nextPoint = 0;
					int seqNum = 16;
					Timer[] timer = new Timer[16];
					TimerTask_[] task = new TimerTask_[16];

					Socket dataChannelSocket = new Socket(serverIP, dataPort);
					System.out.println("###Open Data Link###");

					FileInputStream input = new FileInputStream(f);
					InputStream inACK = dataChannelSocket.getInputStream();
					OutputStream output = dataChannelSocket.getOutputStream();

					System.out.println(fileName + " transferred  / " + fileSize + " bytes");
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
											// NOHTING
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
					System.out.println("Completed...");

					TIMEOUT = DROP = BITERROR = false;
					input.close();
					output.close();
					dataChannelSocket.close();
					inACK.close();

					for (int i = 0; timer[i] != null && i < timer.length; i = (i + 1) % seqNum) {
						timer[i].cancel();
						timer[i] = null;
					}
					timeoutTimer.cancel();
				} else
					System.out.println("Failed for unknown reason");

			}

			outToServer.flush();
		}
	}
}

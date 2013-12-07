/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jxtadoop.hdfs.server.datanode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import net.jxta.impl.util.pipe.reliable.ReliableInputStream;
import net.jxta.impl.util.pipe.reliable.ReliableOutputStream;
import net.jxta.socket.JxtaSocket;
import net.jxta.socket.JxtaSocketAddress;

import org.apache.commons.logging.Log;
import org.apache.jxtadoop.hdfs.protocol.Block;
import org.apache.jxtadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.jxtadoop.hdfs.protocol.DatanodeInfo;
import org.apache.jxtadoop.hdfs.protocol.FSConstants;
import org.apache.jxtadoop.hdfs.server.common.HdfsConstants;
import org.apache.jxtadoop.hdfs.server.datanode.FSDatasetInterface.MetaDataInputStream;
import org.apache.jxtadoop.io.IOUtils;
import org.apache.jxtadoop.io.MD5Hash;
import org.apache.jxtadoop.io.Text;
import org.apache.jxtadoop.net.NetUtils;
import org.apache.jxtadoop.util.DataChecksum;
import org.apache.jxtadoop.util.StringUtils;
import org.apache.jxtadoop.metrics.util.MetricsTimeVaryingRate;
import static org.apache.jxtadoop.hdfs.server.datanode.DataNode.DN_CLIENTTRACE_FORMAT;

/**
 * Thread for processing incoming/outgoing data stream.
 */
@SuppressWarnings({"unused"})
class DataXceiver implements Runnable, FSConstants {
  public static final Log LOG = DataNode.LOG;
  static final Log ClientTraceLog = DataNode.ClientTraceLog;
  
  JxtaSocket s;
  final String remoteAddress; // address of remote side
  final String localAddress;  // local address of this daemon
  DataNode datanode;
  DataXceiverServer dataXceiverServer;
  
  public DataXceiver(JxtaSocket s, DataNode datanode, DataXceiverServer dataXceiverServer) {
    LOG.debug("Kicking off a new DataXceiver");
    this.s = s;
    this.datanode = datanode;
    this.dataXceiverServer = dataXceiverServer;
    dataXceiverServer.childSockets.put(s, s);
    
    // remoteAddress = s.getRemoteSocketAddress().toString();
    remoteAddress = ((JxtaSocketAddress)s.getRemoteSocketAddress()).getPeerId().toString();
    // localAddress = s.getLocalSocketAddress().toString();
    localAddress = ((JxtaSocketAddress)s.getLocalSocketAddress()).getPeerId().toString();
    
    LOG.debug("Number of active connections is: " + datanode.getXceiverCount());
  }

  /**
   * Read/write data from/to the DataXceiveServer.
   */
  public void run() {
    DataInputStream in=null; 
    LOG.debug("DataXceiver starts processing new incoming data");
    
    try {
      //in = new DataInputStream(
      //    new BufferedInputStream(NetUtils.getInputStream(s), 
      //                            SMALL_BUFFER_SIZE)); 
      LOG.debug("Reading version from stream");
	  
      LOG.debug("DataXceiver socket connected : "+s.isConnected());
      LOG.debug("DataXceiver socket closed : "+s.isClosed());
      
      ReliableInputStream ris = (ReliableInputStream) s.getInputStream();
      BufferedInputStream bis = new BufferedInputStream(ris);
      
      in = new DataInputStream(bis); 
	  	  
      short version = in.readShort();
      LOG.debug("Version read : "+version);
      if ( version != DataTransferProtocol.DATA_TRANSFER_VERSION ) {
        throw new IOException( "Version Mismatch" );
      }
      
      //boolean local = s.getInetAddress().equals(s.getLocalAddress());
      boolean local = false; /** TODO A modifier proprement **/
      
      LOG.debug("Reading op type from stream");
      byte op = in.readByte();
      
      LOG.debug("op type read : "+op);
      
      // Make sure the xciver count is not exceeded
      int curXceiverCount = datanode.getXceiverCount();
      if (curXceiverCount > dataXceiverServer.maxXceiverCount) {
        throw new IOException("xceiverCount " + curXceiverCount
                              + " exceeds the limit of concurrent xcievers "
                              + dataXceiverServer.maxXceiverCount);
      }
      
      long startTime = DataNode.now();
     
      switch ( op ) {
	      case DataTransferProtocol.OP_READ_BLOCK:
	    	 LOG.debug("Received a OP_READ_BLOCK op");
	        readBlock( in );
	        datanode.myMetrics.readBlockOp.inc(DataNode.now() - startTime);
	        if (local)
	          datanode.myMetrics.readsFromLocalClient.inc();
	        else
	          datanode.myMetrics.readsFromRemoteClient.inc();
	        break;
	      case DataTransferProtocol.OP_WRITE_BLOCK:
	    	  LOG.debug("Received a OP_WRITE_BLOCK op");
	    	  writeBlock( in );
	        datanode.myMetrics.writeBlockOp.inc(DataNode.now() - startTime);
	        if (local)
	          datanode.myMetrics.writesFromLocalClient.inc();
	        else
	          datanode.myMetrics.writesFromRemoteClient.inc();
	        break;
	      case DataTransferProtocol.OP_READ_METADATA:
	    	  LOG.debug("Received a OP_READ_METADATA op");
	        readMetadata( in );
	        datanode.myMetrics.readMetadataOp.inc(DataNode.now() - startTime);
	        break;
	      case DataTransferProtocol.OP_REPLACE_BLOCK: // for balancing purpose; send to a destination
	    	  LOG.debug("Received a OP_REPLACE_BLOCK op");
	    	  replaceBlock(in);
	        datanode.myMetrics.replaceBlockOp.inc(DataNode.now() - startTime);
	        break;
	      case DataTransferProtocol.OP_COPY_BLOCK:
	            // for balancing purpose; send to a proxy source
	    	  LOG.debug("Received a OP_COPY_BLOCK op");
	    	  copyBlock(in);
	        datanode.myMetrics.copyBlockOp.inc(DataNode.now() - startTime);
	        break;
	      case DataTransferProtocol.OP_BLOCK_CHECKSUM: //get the checksum of a block
	    	  LOG.debug("Received a OP_BLOCK_CHECKSUM op");
	    	  getBlockChecksum(in);
	        datanode.myMetrics.blockChecksumOp.inc(DataNode.now() - startTime);
	        break;
	      default:
	    	  LOG.debug("Unknown op code");
	        throw new IOException("Unknown opcode " + op + " in data stream");
      }
    } catch (SocketTimeoutException ste) {
    	LOG.debug("Time out while receiving data on DataXceiver");
    	LOG.debug(ste);
    	ste.printStackTrace();
    } catch (Exception t) {
        LOG.error(datanode.dnRegistration + ":DataXceiver FAILED",t);
        t.printStackTrace();
    } finally {
      LOG.debug(datanode.dnRegistration + ":Number of active connections is: "
                               + datanode.getXceiverCount());
	      
	  IOUtils.closeStream(in);
      IOUtils.closeSocket(s);
      dataXceiverServer.childSockets.remove(s);
      s = null;
    }
  }

  /**
   * Read a block from the disk.
   * @param in The stream to read from
   * @throws IOException
   */
  private void readBlock(DataInputStream in) throws IOException {
	  LOG.debug("Mathod called : readBlock()");
    //
    // Read in the header
    //
    long blockId = in.readLong();          
    Block block = new Block( blockId, 0 , in.readLong());

    long startOffset = in.readLong();
    long length = in.readLong();
    String clientName = Text.readString(in);
    // send the block
    // OutputStream baseStream = NetUtils.getOutputStream(s, datanode.socketWriteTimeout);
    OutputStream baseStream = s.getOutputStream();
    // DataOutputStream out = new DataOutputStream(
    //             new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));
    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(baseStream));
    
    BlockSender blockSender = null;
    final String clientTraceFmt =
      clientName.length() > 0 && ClientTraceLog.isInfoEnabled()
        ? String.format(DN_CLIENTTRACE_FORMAT, localAddress, remoteAddress,
            "%d", "HDFS_READ", clientName,
            datanode.dnRegistration.getStorageID(), block)
        : datanode.dnRegistration + " Served block " + block + " to " +
            s.getInetAddress();
    try {
      try {
        blockSender = new BlockSender(block, startOffset, length,
            true, true, false, datanode, clientTraceFmt);
      } catch(IOException e) {
        out.writeShort(DataTransferProtocol.OP_STATUS_ERROR);
        throw e;
      }

      out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS); // send op status
      long read = blockSender.sendBlock(out, baseStream, null); // send data

      if (blockSender.isBlockReadFully()) {
        // See if client verification succeeded. 
        // This is an optional response from client.
        try {
          if (in.readShort() == DataTransferProtocol.OP_STATUS_CHECKSUM_OK  && 
              datanode.blockScanner != null) {
            datanode.blockScanner.verifiedByClient(block);
          }
        } catch (IOException ignored) {}
      }
      
      datanode.myMetrics.bytesRead.inc((int) read);
      datanode.myMetrics.blocksRead.inc();
    } catch ( SocketException ignored ) {
      // Its ok for remote side to close the connection anytime.
      datanode.myMetrics.blocksRead.inc();
    } catch ( IOException ioe ) {
      /* What exactly should we do here?
       * Earlier version shutdown() datanode if there is disk error.
       */
      LOG.warn(datanode.dnRegistration +  ":Got exception while serving " + 
          block + " to " +
                s.getInetAddress() + ":\n" + 
                StringUtils.stringifyException(ioe) );
      throw ioe;
    } finally {
    	LOG.debug("Finalizing : readBlock()");
    	IOUtils.closeStream(out);
      IOUtils.closeStream(blockSender);
    }
  }

  /**
   * Write a block to disk.
   * 
   * @param in The stream to read from
   * @throws IOException
   */
  private void writeBlock(DataInputStream in) throws IOException {
	  LOG.debug("Mathod called : writeBlock()");
    DatanodeInfo srcDataNode = null;
    LOG.debug("writeBlock receive buf size " + s.getReceiveBufferSize() +
              " tcp no delay " + s.getTcpNoDelay());
    //
    // Read in the header
    //
    Block block = new Block(in.readLong(), 
        dataXceiverServer.estimateBlockSize, in.readLong());
    LOG.info("Receiving block " + block + 
             " src: " + remoteAddress +
             " dest: " + localAddress);
    
    int pipelineSize = in.readInt(); // num of datanodes in entire pipeline
    boolean isRecovery = in.readBoolean(); // is this part of recovery?
    String client = Text.readString(in); // working on behalf of this client
    
    boolean hasSrcDataNode = in.readBoolean(); // is src node info present
    if (hasSrcDataNode) {
      srcDataNode = new DatanodeInfo();
      srcDataNode.readFields(in);
    }
    
    int numTargets = in.readInt();
    if (numTargets < 0) {
      throw new IOException("Mislabelled incoming datastream.");
    }
    
    DatanodeInfo targets[] = new DatanodeInfo[numTargets];
    for (int i = 0; i < targets.length; i++) {
      DatanodeInfo tmp = new DatanodeInfo();
      tmp.readFields(in);
      targets[i] = tmp;
    }

    DataOutputStream mirrorOut = null;  // stream to next target
    DataInputStream mirrorIn = null;    // reply from next target
    DataOutputStream replyOut = null;   // stream to prev target
    JxtaSocket mirrorSock = null;           // socket to next target
    BlockReceiver blockReceiver = null; // responsible for data handling
    String mirrorNode = null;           // the name:port of next target
    String firstBadLink = "";           // first datanode that failed in connection setup
    
    try {
      // open a block receiver and check if the block does not exist
      /*blockReceiver = new BlockReceiver(block, in, 
          s.getRemoteSocketAddress().toString(),
          s.getLocalSocketAddress().toString(),
          isRecovery, client, srcDataNode, datanode);*/
    	blockReceiver = new BlockReceiver(block, in, 
    			((JxtaSocketAddress)s.getRemoteSocketAddress()).getPeerId().toString(),
    			((JxtaSocketAddress)s.getLocalSocketAddress()).getPeerId().toString(),
    	          isRecovery, client, srcDataNode, datanode);

      // get a connection back to the previous target
      //replyOut = new DataOutputStream(
    	//	  NetUtils.getOutputStream(s, datanode.socketWriteTimeout));
    	ReliableOutputStream replyOutRos = (ReliableOutputStream) s.getOutputStream(); 
    	replyOut = new DataOutputStream(replyOutRos);
      
      //
      // Open network conn to backup machine, if 
      // appropriate
      //
      if (targets.length > 0) {
        // JxtaSocketAddress mirrorTarget = null;
        // Connect to backup machine
        mirrorNode = targets[0].getPeerId();
        // mirrorTarget = NetUtils.createSocketAddr(mirrorNode);
        // mirrorSock = datanode.newSocket();
        
        
        try {
          //int timeoutValue = numTargets * datanode.socketTimeout;
          //int writeTimeout = datanode.socketWriteTimeout + 
          //                   (HdfsConstants.WRITE_TIMEOUT_EXTENSION * numTargets);
          // NetUtils.connect(mirrorSock, mirrorTarget, timeoutValue);
          mirrorSock = datanode.getDnPeer().getInfoSocket(mirrorNode.toString());
          if(mirrorSock == null)
        	  throw new IOException("Failed to get a mirror socket");
          //mirrorSock.setSoTimeout(timeoutValue);
          //mirrorSock.setTcpNoDelay(true);
          //mirrorSock.setSoTimeout(Integer.parseInt(datanode.getConf().get("hadoop.p2p.info.timeout")));
          //mirrorSock.setSendBufferSize(DEFAULT_DATA_SOCKET_SIZE);
          /*mirrorOut = new DataOutputStream(
             new BufferedOutputStream(
            		 NetUtils.getOutputStream(mirrorSock, writeTimeout),
                         SMALL_BUFFER_SIZE));
          mirrorIn = new DataInputStream(NetUtils.getInputStream(mirrorSock));
          */
          mirrorOut = new DataOutputStream((ReliableOutputStream)mirrorSock.getOutputStream());
          mirrorIn = new DataInputStream((ReliableInputStream)mirrorSock.getInputStream());

          // Write header: Copied from DFSClient.java!
          mirrorOut.writeShort( DataTransferProtocol.DATA_TRANSFER_VERSION );
          mirrorOut.write( DataTransferProtocol.OP_WRITE_BLOCK );
          mirrorOut.writeLong( block.getBlockId() );
          mirrorOut.writeLong( block.getGenerationStamp() );
          mirrorOut.writeInt( pipelineSize );
          mirrorOut.writeBoolean( isRecovery );
          Text.writeString( mirrorOut, client );
          mirrorOut.writeBoolean(hasSrcDataNode);
          
          if (hasSrcDataNode) { // pass src node information
            srcDataNode.write(mirrorOut);
          }
          
          mirrorOut.writeInt( targets.length - 1 );
          for ( int i = 1; i < targets.length; i++ ) {
            targets[i].write( mirrorOut );
          }

          blockReceiver.writeChecksumHeader(mirrorOut);
          mirrorOut.flush();

          // read connect ack (only for clients, not for replication req)
          if (client.length() != 0) {
            firstBadLink = Text.readString(mirrorIn);
            if (LOG.isDebugEnabled() || firstBadLink.length() > 0) {
              LOG.info("Datanode " + targets.length +
                       " got response for connect ack " +
                       " from downstream datanode with firstbadlink as " +
                       firstBadLink);
            }
          }

        } catch (SocketTimeoutException ste) {
        	LOG.debug("Time out while receiving data on DataXceiver");
        	LOG.debug(ste);
        	ste.printStackTrace();
        }
        catch (IOException e) {
        	LOG.debug("IOException occurred : "+e.getMessage());
          if (client.length() != 0) {
            Text.writeString(replyOut, mirrorNode);
            replyOut.flush();
          }
          IOUtils.closeStream(mirrorOut);
          mirrorOut = null;
          IOUtils.closeStream(mirrorIn);
          mirrorIn = null;
          if(mirrorSock != null) {
        	  IOUtils.closeSocket(mirrorSock);
        	  mirrorSock = null;
          }
          if (client.length() > 0) {
            throw e;
          } else {
            LOG.info(datanode.dnRegistration + ":Exception transfering block " +
                     block + " to mirror " + mirrorNode +
                     ". continuing without the mirror.\n" +
                     StringUtils.stringifyException(e));
          }
        }
      }

      // send connect ack back to source (only for clients)
      if (client.length() != 0) {
        if (LOG.isDebugEnabled() || firstBadLink.length() > 0) {
          LOG.info("Datanode " + targets.length +
                   " forwarding connect ack to upstream firstbadlink is " +
                   firstBadLink);
        }
        Text.writeString(replyOut, firstBadLink);
        replyOut.flush();
      }

      // receive the block and mirror to the next target
      String mirrorAddr = (mirrorSock == null) ? null : mirrorNode;
      blockReceiver.receiveBlock(mirrorOut, mirrorIn, replyOut,
                                 mirrorAddr, null, targets.length);

      // if this write is for a replication request (and not
      // from a client), then confirm block. For client-writes,
      // the block is finalized in the PacketResponder.
      if (client.length() == 0) {
        datanode.notifyNamenodeReceivedBlock(block, DataNode.EMPTY_DEL_HINT);
        LOG.info("Received block " + block + 
                 " src: " + remoteAddress +
                 " dest: " + localAddress +
                 " of size " + block.getNumBytes());
      }

      if (datanode.blockScanner != null) {
        datanode.blockScanner.addBlock(block);
      }
      
    } catch (IOException ioe) {
      LOG.info("writeBlock " + block + " received exception " + ioe);
      throw ioe;
    } catch (Exception e) {
    	LOG.warn("Exception occurred in writting block : "+e.getMessage());
    } finally {
      // close all opened streams
      
      LOG.debug("Finalizing : writeBlock()");
      IOUtils.closeStream(mirrorOut);
      IOUtils.closeStream(mirrorIn);
      IOUtils.closeStream(replyOut);
      IOUtils.closeSocket(mirrorSock);
      IOUtils.closeStream(blockReceiver);
    }
  }

  /**
   * Reads the metadata and sends the data in one 'DATA_CHUNK'.
   * @param in
   */
  void readMetadata(DataInputStream in) throws IOException {
	LOG.debug("Mathod called : readMetadata()");
    Block block = new Block( in.readLong(), 0 , in.readLong());
    MetaDataInputStream checksumIn = null;
    DataOutputStream out = null;
    
    try {

      checksumIn = datanode.data.getMetaDataInputStream(block);
      
      long fileSize = checksumIn.getLength();

      if (fileSize >= 1L<<31 || fileSize <= 0) {
          throw new IOException("Unexpected size for checksumFile of block" +
                  block);
      }

      byte [] buf = new byte[(int)fileSize];
      IOUtils.readFully(checksumIn, buf, 0, buf.length);
      
      //out = new DataOutputStream(
      // 		  NetUtils.getOutputStream(s, datanode.socketWriteTimeout));
	out = new DataOutputStream(s.getOutputStream());
      
      out.writeByte(DataTransferProtocol.OP_STATUS_SUCCESS);
      out.writeInt(buf.length);
      out.write(buf);
      
      //last DATA_CHUNK
      out.writeInt(0);
    } finally {
    	LOG.debug("Finalizing : readMetadata()");
     IOUtils.closeStream(out);
      IOUtils.closeStream(checksumIn);
    }
  }
  
  /**
   * Get block checksum (MD5 of CRC32).
   * @param in
   */
  void getBlockChecksum(DataInputStream in) throws IOException {
	  LOG.debug("Mathod called : getBlockChecksum()");
    final Block block = new Block(in.readLong(), 0 , in.readLong());

    DataOutputStream out = null;
    final MetaDataInputStream metadataIn = datanode.data.getMetaDataInputStream(block);
    final DataInputStream checksumIn = new DataInputStream(new BufferedInputStream(
        metadataIn, BUFFER_SIZE));

    try {
      //read metadata file
      final BlockMetadataHeader header = BlockMetadataHeader.readHeader(checksumIn);
      final DataChecksum checksum = header.getChecksum(); 
      final int bytesPerCRC = checksum.getBytesPerChecksum();
      final long crcPerBlock = (metadataIn.getLength()
          - BlockMetadataHeader.getHeaderSize())/checksum.getChecksumSize();
      
      //compute block checksum
      final MD5Hash md5 = MD5Hash.digest(checksumIn);

      if (LOG.isDebugEnabled()) {
        LOG.debug("block=" + block + ", bytesPerCRC=" + bytesPerCRC
            + ", crcPerBlock=" + crcPerBlock + ", md5=" + md5);
      }

      //write reply
      //out = new DataOutputStream(
      //		  NetUtils.getOutputStream(s, datanode.socketWriteTimeout));
	out = new DataOutputStream(s.getOutputStream());
      out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
      out.writeInt(bytesPerCRC);
      out.writeLong(crcPerBlock);
      md5.write(out);
      out.flush();
    } finally {
    	LOG.debug("Finalizing : getBlockChecksum()");
      IOUtils.closeStream(out);
      IOUtils.closeStream(checksumIn);
      IOUtils.closeStream(metadataIn);
    }
  }

  /**
   * Read a block from the disk and then sends it to a destination.
   * 
   * @param in The stream to read from
   * @throws IOException
   */
  private void copyBlock(DataInputStream in) throws IOException {
	  LOG.debug("Mathod called : copyBlock()");
    // Read in the header
    long blockId = in.readLong(); // read block id
    Block block = new Block(blockId, 0, in.readLong());

    if (!dataXceiverServer.balanceThrottler.acquire()) { // not able to start
      LOG.info("Not able to copy block " + blockId + " to " 
          + s.getRemoteSocketAddress() + " because threads quota is exceeded.");
      return;
    }

    BlockSender blockSender = null;
    DataOutputStream reply = null;
    boolean isOpSuccess = true;

    try {
      // check if the block exists or not
      blockSender = new BlockSender(block, 0, -1, false, false, false, 
          datanode);

      // set up response stream
      //OutputStream baseStream = NetUtils.getOutputStream(s, datanode.socketWriteTimeout);
      OutputStream baseStream = s.getOutputStream();
      
      //reply = new DataOutputStream(new BufferedOutputStream(
      //    baseStream, SMALL_BUFFER_SIZE));
      LOG.debug("Replying to DFS client");
	reply = new DataOutputStream(new BufferedOutputStream(baseStream));

      // send block content to the target
      long read = blockSender.sendBlock(reply, baseStream, 
                                        dataXceiverServer.balanceThrottler);

      datanode.myMetrics.bytesRead.inc((int) read);
      datanode.myMetrics.blocksRead.inc();
      
      LOG.info("Copied block " + block + " to " + s.getRemoteSocketAddress());
    } catch (IOException ioe) {
      isOpSuccess = false;
      throw ioe;
    } finally {
      dataXceiverServer.balanceThrottler.release();
      if (isOpSuccess) {
        try {
          // send one last byte to indicate that the resource is cleaned.
          reply.writeChar('d');
        } catch (IOException ignored) {
        }
      }
      LOG.debug("Finalizing : copyBlock()");
      IOUtils.closeStream(reply);
      IOUtils.closeStream(blockSender);
    }
  }

  /**
   * Receive a block and write it to disk, it then notifies the namenode to
   * remove the copy from the source.
   * 
   * @param in The stream to read from
   * @throws IOException
   */
  private void replaceBlock(DataInputStream in) throws IOException {
	  LOG.debug("Mathod called : replaceBlock()");
	  /* read header */
    long blockId = in.readLong();
    Block block = new Block(blockId, dataXceiverServer.estimateBlockSize,
        in.readLong()); // block id & generation stamp
    String sourceID = Text.readString(in); // read del hint
    DatanodeInfo proxySource = new DatanodeInfo(); // read proxy source
    proxySource.readFields(in);

    if (!dataXceiverServer.balanceThrottler.acquire()) { // not able to start
      LOG.warn("Not able to receive block " + blockId + " from " 
          + s.getRemoteSocketAddress() + " because threads quota is exceeded.");
      sendResponse(s, (short)DataTransferProtocol.OP_STATUS_ERROR, 
          datanode.socketWriteTimeout);
      return;
    }

    JxtaSocket proxySock = null;
    DataOutputStream proxyOut = null;
    short opStatus = DataTransferProtocol.OP_STATUS_SUCCESS;
    BlockReceiver blockReceiver = null;
    DataInputStream proxyReply = null;
    ReliableOutputStream baseStream = null;
    ReliableInputStream replyStream = null;
    
    try {
      // get the output stream to the proxy
      //InetSocketAddress proxyAddr = NetUtils.createSocketAddr(
      //    proxySource.getName());
      //proxySock = datanode.newSocket();
	proxySock = datanode.getDnPeer().getInfoSocket(proxySource.getPeerId().toString());

      // NetUtils.connect(proxySock, proxyAddr, datanode.socketTimeout);
      // proxySock.setSoTimeout(datanode.socketTimeout);

      /*OutputStream baseStream = NetUtils.getOutputStream(proxySock, 
          datanode.socketWriteTimeout);
      proxyOut = new DataOutputStream(
                     new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));
	*/
	baseStream = (ReliableOutputStream) proxySock.getOutputStream();	
	proxyOut = new DataOutputStream(new BufferedOutputStream(baseStream));

      /* send request to the proxy */
      proxyOut.writeShort(DataTransferProtocol.DATA_TRANSFER_VERSION); // transfer version
      proxyOut.writeByte(DataTransferProtocol.OP_COPY_BLOCK); // op code
      proxyOut.writeLong(block.getBlockId()); // block id
      proxyOut.writeLong(block.getGenerationStamp()); // block id
      proxyOut.flush();

      // receive the response from the proxy
      //proxyReply = new DataInputStream(new BufferedInputStream(
      //    NetUtils.getInputStream(proxySock), BUFFER_SIZE));
      replyStream = (ReliableInputStream) proxySock.getInputStream();
      proxyReply = new DataInputStream(new BufferedInputStream(replyStream));
      // open a block receiver and check if the block does not exist
      blockReceiver = new BlockReceiver(
          block, proxyReply, proxySock.getRemoteSocketAddress().toString(),
          proxySock.getLocalSocketAddress().toString(),
          false, "", null, datanode);

      // receive a block
      blockReceiver.receiveBlock(null, null, null, null, 
          dataXceiverServer.balanceThrottler, -1);
                    
      // notify name node
      datanode.notifyNamenodeReceivedBlock(block, sourceID);

      LOG.info("Moved block " + block + 
          " from " + s.getRemoteSocketAddress());
      
    } catch (IOException ioe) {
      opStatus = DataTransferProtocol.OP_STATUS_ERROR;
      throw ioe;
    } finally {
      // receive the last byte that indicates the proxy released its thread resource
      if (opStatus == DataTransferProtocol.OP_STATUS_SUCCESS) {
        try {
          proxyReply.readChar();
        } catch (IOException ignored) {
        }
      }
      
      // now release the thread resource
      dataXceiverServer.balanceThrottler.release();
      
      // send response back
      try {
        sendResponse(s, opStatus, datanode.socketWriteTimeout);
      } catch (IOException ioe) {
        LOG.warn("Error writing reply back to " + s.getRemoteSocketAddress());
      }
      
      LOG.debug("Finalizing : replaceBlock()");
      LOG.debug("baseStream queue empty : "+baseStream.isQueueEmpty());
      IOUtils.closeStream(proxyOut);
      IOUtils.closeStream(blockReceiver);
      IOUtils.closeStream(proxyReply);
    }
  }
  
  /**
   * Utility function for sending a response.
   * @param s socket to write to
   * @param opStatus status message to write
   * @param timeout send timeout
   **/
  private void sendResponse(JxtaSocket s, short opStatus, long timeout) 
                                                       throws IOException {
	  LOG.debug("Mathod called : sendResponse()");
	  //DataOutputStream reply = 
    //  new DataOutputStream(NetUtils.getOutputStream(s, timeout));
	ReliableOutputStream ros = (ReliableOutputStream)  s.getOutputStream();
	DataOutputStream reply = new DataOutputStream(s.getOutputStream());
	
    try {
      reply.writeShort(opStatus);
      reply.flush();
    } finally {
    	LOG.debug("Finalizing : sendResponse()");
      LOG.debug("sendReponse stream queue empty : "+ros.isQueueEmpty());
      // IOUtils.closeStream(reply);
    }
  }
}

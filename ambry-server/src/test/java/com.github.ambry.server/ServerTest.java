package com.github.ambry.server;

import com.github.ambry.clustermap.*;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.coordinator.AmbryCoordinator;
import com.github.ambry.coordinator.Coordinator;
import com.github.ambry.coordinator.CoordinatorException;
import com.github.ambry.messageformat.*;
import com.github.ambry.replication.ReplicationException;
import com.github.ambry.shared.*;
import com.github.ambry.store.*;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.CrcInputStream;
import com.github.ambry.utils.Utils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Random;
import java.util.List;

public class ServerTest {

  private List<AmbryServer> serverList = null;
  private MockClusterMap clusterMap = null;

  public ServerTest() throws InterruptedException, IOException, StoreException, InstantiationException {

    clusterMap = new MockClusterMap();
    serverList = new ArrayList<AmbryServer>();
    DataNodeId dataNodeId1 = clusterMap.getDataNodeId("localhost", 6667);
    setReplicas(dataNodeId1);

    DataNodeId dataNodeId2 = clusterMap.getDataNodeId("localhost", 6668);
    setReplicas(dataNodeId2);

    DataNodeId dataNodeId3 = clusterMap.getDataNodeId("localhost", 6669);
    setReplicas(dataNodeId3);
  }

  private void setReplicas(DataNodeId dataNodeId) throws IOException, InstantiationException {
    for (ReplicaId replicaId : clusterMap.getReplicaIds(dataNodeId)) {
      Properties props = new Properties();
      props.setProperty("host.name", "localhost");
      props.setProperty("port", Integer.toString(replicaId.getDataNodeId().getPort()));
      VerifiableProperties propverify = new VerifiableProperties(props);
      AmbryServer server = new AmbryServer(propverify, clusterMap);
      server.startup();
      serverList.add(server);
    }
  }

  @After
  public void cleanup() {
    for (AmbryServer server : serverList)
      server.shutdown();
    clusterMap.cleanup();
  }

  @Test
  public void EndToEndTest() throws InterruptedException, IOException {

    try {
      byte[] usermetadata = new byte[1000];
      byte[] data = new byte[31870];
      BlobProperties properties = new BlobProperties(31870, "serviceid1");
      new Random().nextBytes(usermetadata);
      new Random().nextBytes(data);
      BlobId blobId1 = new BlobId(new MockPartitionId(null));
      BlobId blobId2 = new BlobId(new MockPartitionId(null));
      BlobId blobId3 = new BlobId(new MockPartitionId(null));
      // put blob 1
      PutRequest putRequest = new PutRequest(1,
                                             "client1",
                                             blobId1,
                                             properties, ByteBuffer.wrap(usermetadata),
                                             new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      BlockingChannel channel = new BlockingChannel("localhost", 6667, 10000, 10000, 10000);
      channel.connect();
      channel.send(putRequest);
      InputStream putResponseStream = channel.receive();
      PutResponse response = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response.getError(), ServerErrorCode.No_Error);

      // put blob 2
      PutRequest putRequest2 = new PutRequest(1,
                                              "client1",
                                              blobId2,
                                              properties, ByteBuffer.wrap(usermetadata),
                                              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel.send(putRequest2);
      putResponseStream = channel.receive();
      PutResponse response2 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response2.getError(), ServerErrorCode.No_Error);

      // put blob 3
      PutRequest putRequest3 = new PutRequest(1,
                                              "client1",
                                              blobId3,
                                              properties, ByteBuffer.wrap(usermetadata),
                                              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel.send(putRequest3);
      putResponseStream = channel.receive();
      PutResponse response3 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response3.getError(), ServerErrorCode.No_Error);

      // get blob properties
      ArrayList<BlobId> ids = new ArrayList<BlobId>();
      MockPartitionId partition = new MockPartitionId(null);
      ids.add(blobId1);
      GetRequest getRequest1 = new GetRequest(1, "clientid2", MessageFormatFlags.BlobProperties, partition, ids);
      channel.send(getRequest1);
      InputStream stream = channel.receive();
      GetResponse resp1 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      try {
        BlobProperties propertyOutput = MessageFormatRecord.deserializeBlobProperties(resp1.getInputStream());
        Assert.assertEquals(propertyOutput.getBlobSize(), 31870);
        Assert.assertEquals(propertyOutput.getServiceId(), "serviceid1");
      }
      catch (MessageFormatException e) {
        Assert.assertEquals(false, true);
      }

      // get user metadata
      GetRequest getRequest2 = new GetRequest(1, "clientid2", MessageFormatFlags.BlobUserMetadata, partition, ids);
      channel.send(getRequest2);
      stream = channel.receive();
      GetResponse resp2 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      try {
        ByteBuffer userMetadataOutput = MessageFormatRecord.deserializeUserMetadata(resp2.getInputStream());
        Assert.assertArrayEquals(userMetadataOutput.array(), usermetadata);
      }
      catch (MessageFormatException e) {
        Assert.assertEquals(false, true);
      }

      try {
        // get blob data
        // Use coordinator to get the blob
        Coordinator coordinator = new AmbryCoordinator(getCoordinatorProperties(), clusterMap);
        coordinator.start();
        BlobOutput output = coordinator.getBlob(blobId1.toString());
        Assert.assertEquals(output.getSize(), 31870);
        byte[] dataOutputStream = new byte[(int)output.getSize()];
        output.getStream().read(dataOutputStream);
        Assert.assertArrayEquals(dataOutputStream, data);
        coordinator.shutdown();
      }
      catch (CoordinatorException e) {
        e.printStackTrace();
        Assert.assertEquals(false, true);
      }

      // fetch blob that does not exist
      // get blob properties
      ids = new ArrayList<BlobId>();
      partition = new MockPartitionId(null);
      ids.add(new BlobId(partition));
      GetRequest getRequest4 = new GetRequest(1, "clientid2", MessageFormatFlags.BlobProperties, partition, ids);
      channel.send(getRequest4);
      stream = channel.receive();
      GetResponse resp4 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      Assert.assertEquals(resp4.getError(), ServerErrorCode.Blob_Not_Found);
      channel.disconnect();
    }
    catch (Exception e) {
      e.printStackTrace();
      Assert.assertEquals(true, false);
    }
  }

  @Test
  public void EndToEndReplicationTest() throws InterruptedException, IOException {

    try {
      byte[] usermetadata = new byte[1000];
      byte[] data = new byte[1000];
      BlobProperties properties = new BlobProperties(1000, "serviceid1");
      new Random().nextBytes(usermetadata);
      new Random().nextBytes(data);
      BlobId blobId1 = new BlobId(new MockPartitionId(null));
      BlobId blobId2 = new BlobId(new MockPartitionId(null));
      BlobId blobId3 = new BlobId(new MockPartitionId(null));
      BlobId blobId4 = new BlobId(new MockPartitionId(null));
      BlobId blobId5 = new BlobId(new MockPartitionId(null));
      BlobId blobId6 = new BlobId(new MockPartitionId(null));
      BlobId blobId7 = new BlobId(new MockPartitionId(null));
      BlobId blobId8 = new BlobId(new MockPartitionId(null));
      BlobId blobId9 = new BlobId(new MockPartitionId(null));
      BlobId blobId10 = new BlobId(new MockPartitionId(null));
      BlobId blobId11 = new BlobId(new MockPartitionId(null));

      // put blob 1
      PutRequest putRequest = new PutRequest(1,
              "client1",
              blobId1,
              properties, ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      BlockingChannel channel1 = new BlockingChannel("localhost", 6667, 10000, 10000, 10000);
      BlockingChannel channel2 = new BlockingChannel("localhost", 6668, 10000, 10000, 10000);
      BlockingChannel channel3 = new BlockingChannel("localhost", 6669, 10000, 10000, 10000);

      channel1.connect();
      channel2.connect();
      channel3.connect();
      channel1.send(putRequest);
      InputStream putResponseStream = channel1.receive();
      PutResponse response = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response.getError(), ServerErrorCode.No_Error);

      // put blob 2
      PutRequest putRequest2 = new PutRequest(1,
              "client1",
              blobId2,
              properties, ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel2.send(putRequest2);
      putResponseStream = channel2.receive();
      PutResponse response2 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response2.getError(), ServerErrorCode.No_Error);

      // put blob 3
      PutRequest putRequest3 = new PutRequest(1,
              "client1",
              blobId3,
              properties, ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel3.send(putRequest3);
      putResponseStream = channel3.receive();
      PutResponse response3 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response3.getError(), ServerErrorCode.No_Error);

      // put blob 4
      putRequest = new PutRequest(1,
              "client1",
              blobId4,
              properties, ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel1.send(putRequest);
      putResponseStream = channel1.receive();
      response = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response.getError(), ServerErrorCode.No_Error);

      // put blob 5
      putRequest2 = new PutRequest(1,
              "client1",
              blobId5,
              properties, ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel2.send(putRequest2);
      putResponseStream = channel2.receive();
      response2 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response2.getError(), ServerErrorCode.No_Error);

      // put blob 6
      putRequest3 = new PutRequest(1,
              "client1",
              blobId6,
              properties, ByteBuffer.wrap(usermetadata),
              new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel3.send(putRequest3);
      putResponseStream = channel3.receive();
      response3 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response3.getError(), ServerErrorCode.No_Error);

      // wait till replication can complete
      Thread.sleep(4000);

      // get blob properties
      ArrayList<BlobId> ids = new ArrayList<BlobId>();
      MockPartitionId partition = new MockPartitionId(null);
      ids.add(blobId3);
      GetRequest getRequest1 = new GetRequest(1, "clientid2", MessageFormatFlags.BlobProperties, partition, ids);
      channel2.send(getRequest1);
      InputStream stream = channel2.receive();
      GetResponse resp1 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      try {
        BlobProperties propertyOutput = MessageFormatRecord.deserializeBlobProperties(resp1.getInputStream());
        Assert.assertEquals(propertyOutput.getBlobSize(), 1000);
        Assert.assertEquals(propertyOutput.getServiceId(), "serviceid1");
      }
      catch (MessageFormatException e) {
        Assert.assertEquals(false, true);
      }

      // get user metadata
      ids.clear();
      ids.add(blobId2);
      GetRequest getRequest2 = new GetRequest(1, "clientid2", MessageFormatFlags.BlobUserMetadata, partition, ids);
      channel1.send(getRequest2);
      stream = channel1.receive();
      GetResponse resp2 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      try {
        ByteBuffer userMetadataOutput = MessageFormatRecord.deserializeUserMetadata(resp2.getInputStream());
        Assert.assertArrayEquals(userMetadataOutput.array(), usermetadata);
      }
      catch (MessageFormatException e) {
        Assert.assertEquals(false, true);
      }

      // get blob
      ids.clear();
      ids.add(blobId1);
      GetRequest getRequest3 = new GetRequest(1, "clientid2", MessageFormatFlags.Blob, partition, ids);
      channel3.send(getRequest3);
      stream = channel3.receive();
      GetResponse resp3 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      System.out.println("response from get " + resp3.getError());
      try {
        BlobOutput blobOutput = MessageFormatRecord.deserializeBlob(resp3.getInputStream());
        byte[] blobout = new byte[(int)blobOutput.getSize()];
        int readsize = 0;
        while (readsize < blobOutput.getSize()) {
          readsize += blobOutput.getStream().read(blobout, readsize, (int)blobOutput.getSize() - readsize);
        }
        Assert.assertArrayEquals(blobout, data);
      }
      catch (MessageFormatException e) {
        Assert.assertEquals(false, true);
      }

      try {
        // get blob data
        // Use coordinator to get the blob
        Coordinator coordinator = new AmbryCoordinator(getCoordinatorProperties(), clusterMap);
        coordinator.start();
        checkBlobId(coordinator, blobId1, data);
        checkBlobId(coordinator, blobId2, data);
        checkBlobId(coordinator, blobId3, data);
        checkBlobId(coordinator, blobId4, data);
        checkBlobId(coordinator, blobId5, data);
        checkBlobId(coordinator, blobId6, data);

        coordinator.shutdown();
      }
      catch (CoordinatorException e) {
        e.printStackTrace();
        Assert.assertEquals(false, true);
      }

      // fetch blob that does not exist
      // get blob properties
      ids = new ArrayList<BlobId>();
      partition = new MockPartitionId(null);
      ids.add(new BlobId(partition));
      GetRequest getRequest4 = new GetRequest(1, "clientid2", MessageFormatFlags.BlobProperties, partition, ids);
      channel3.send(getRequest4);
      stream = channel3.receive();
      GetResponse resp4 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      Assert.assertEquals(resp4.getError(), ServerErrorCode.Blob_Not_Found);

      // delete a blob and ensure it is propagated
      DeleteRequest deleteRequest = new DeleteRequest(1, "reptest", blobId1);
      channel1.send(deleteRequest);
      InputStream deleteResponseStream = channel1.receive();
      DeleteResponse deleteResponse = DeleteResponse.readFrom(new DataInputStream(deleteResponseStream));
      Assert.assertEquals(deleteResponse.getError(), ServerErrorCode.No_Error);

      Thread.sleep(3000);

      ids = new ArrayList<BlobId>();
      ids.add(blobId1);
      GetRequest getRequest5 = new GetRequest(1, "clientid2", MessageFormatFlags.Blob, partition, ids);
      channel3.send(getRequest5);
      stream = channel3.receive();
      GetResponse resp5 = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
      Assert.assertEquals(resp5.getError(), ServerErrorCode.Blob_Deleted);

      Thread.sleep(3000);
      // persist and restore to check state

      serverList.get(0).shutdown();
      serverList.get(0).awaitShutdown();

      // read the replica file and check correctness
      DataNodeId dataNodeId = clusterMap.getDataNodeId("localhost", 6667);
      File replicaTokenFile = new File(clusterMap.getReplicaIds(dataNodeId).get(0).getMountPath(), "replicaTokens");
      if (replicaTokenFile.exists()) {
        CrcInputStream crcStream = new CrcInputStream(new FileInputStream(replicaTokenFile));
        DataInputStream dataInputStream = new DataInputStream(crcStream);
        try {
          short version = dataInputStream.readShort();
          Assert.assertEquals(version, 0);
          StoreKeyFactory storeKeyFactory = Utils.getObj("com.github.ambry.shared.BlobIdFactory", clusterMap);
          FindTokenFactory factory = Utils.getObj("com.github.ambry.store.StoreFindTokenFactory", storeKeyFactory);
          while (dataInputStream.available() > 8) {
            // read partition id
            PartitionId partitionId = clusterMap.getPartitionIdFromStream(dataInputStream);
            Assert.assertEquals(partitionId, clusterMap.getReplicaIds(dataNodeId).get(0).getPartitionId());
            // read remote node host name
            String hostname = Utils.readIntString(dataInputStream);
            Assert.assertEquals(hostname, "127.0.0.1");
            // read remote port
            int port = dataInputStream.readInt();
            Assert.assertTrue(port == 6668 || port == 6669);
            // read replica token
            FindToken token = factory.getFindToken(dataInputStream);
            ByteBuffer bytebufferToken = ByteBuffer.wrap(token.toBytes());
            Assert.assertEquals(bytebufferToken.getShort(), 0);
            Assert.assertEquals(bytebufferToken.getLong(), 13086);
          }
          long crc = crcStream.getValue();
          Assert.assertEquals(crc, dataInputStream.readLong());
        }
        catch (IOException e) {
          Assert.assertTrue(false);
        }
        finally {
          dataInputStream.close();
        }
      }

      // Add more data to server 2 and server 3. Recover server 1 and ensure it is completely replicated
      // put blob 7
      putRequest2 = new PutRequest(1,
                                   "client1",
                                   blobId7,
                                   properties, ByteBuffer.wrap(usermetadata),
                                   new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel2.send(putRequest2);
      putResponseStream = channel2.receive();
      response2 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response2.getError(), ServerErrorCode.No_Error);

      // put blob 8
      putRequest3 = new PutRequest(1,
                                   "client1",
                                   blobId8,
                                   properties, ByteBuffer.wrap(usermetadata),
                                   new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel3.send(putRequest3);
      putResponseStream = channel3.receive();
      response3 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response3.getError(), ServerErrorCode.No_Error);

      // put blob 9
      putRequest2 = new PutRequest(1,
                                   "client1",
                                   blobId9,
                                   properties, ByteBuffer.wrap(usermetadata),
                                   new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel2.send(putRequest2);
      putResponseStream = channel2.receive();
      response2 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response2.getError(), ServerErrorCode.No_Error);

      // put blob 10
      putRequest3 = new PutRequest(1,
                                   "client1",
                                   blobId10,
                                   properties, ByteBuffer.wrap(usermetadata),
                                   new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel3.send(putRequest3);
      putResponseStream = channel3.receive();
      response3 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response3.getError(), ServerErrorCode.No_Error);

      // put blob 11
      putRequest2 = new PutRequest(1,
                                   "client1",
                                   blobId11,
                                   properties, ByteBuffer.wrap(usermetadata),
                                   new ByteBufferInputStream(ByteBuffer.wrap(data))
      );
      channel2.send(putRequest2);
      putResponseStream = channel2.receive();
      response2 = PutResponse.readFrom(new DataInputStream(putResponseStream));
      Assert.assertEquals(response2.getError(), ServerErrorCode.No_Error);

      serverList.get(0).startup();
      // wait for server to recover
      Thread.sleep(3000);
      channel1.disconnect();
      channel1.connect();

      // check all ids exist on server 1
      // get blob
      try {
        checkBlobContent(blobId2, channel1, data);
        checkBlobContent(blobId3, channel1, data);
        checkBlobContent(blobId4, channel1, data);
        checkBlobContent(blobId5, channel1, data);
        checkBlobContent(blobId6, channel1, data);
        checkBlobContent(blobId7, channel1, data);
        checkBlobContent(blobId8, channel1, data);
        checkBlobContent(blobId9, channel1, data);
        checkBlobContent(blobId10, channel1, data);
        checkBlobContent(blobId11, channel1, data);
      }
      catch (MessageFormatException e) {
        Assert.assertFalse(true);
      }

      // Shutdown server 1. Remove all its data from all mount path. Recover server 1 and ensure node is built
      serverList.get(0).shutdown();
      serverList.get(0).awaitShutdown();

      File mountFile = new File(clusterMap.getReplicaIds(dataNodeId).get(0).getMountPath());
      for (File toDelete: mountFile.listFiles()) {
        deleteFolderContent(toDelete);
      }

      serverList.get(0).startup();
      Thread.sleep(3000);
      channel1.disconnect();
      channel1.connect();

      // check all ids exist on server 1
      // get blob
      try {
        checkBlobContent(blobId2, channel1, data);
        checkBlobContent(blobId3, channel1, data);
        checkBlobContent(blobId4, channel1, data);
        checkBlobContent(blobId5, channel1, data);
        checkBlobContent(blobId6, channel1, data);
        checkBlobContent(blobId7, channel1, data);
        checkBlobContent(blobId8, channel1, data);
        checkBlobContent(blobId9, channel1, data);
        checkBlobContent(blobId10, channel1, data);
        checkBlobContent(blobId11, channel1, data);
      }
      catch (MessageFormatException e) {
        Assert.assertFalse(true);
      }

      // Shutdown server 1. Clean up a disk. Recover server 1 and ensure disk is built

      // Add more replica threads than mount path and ensure partitions are distributed across threads

      channel1.disconnect();
      channel2.disconnect();
      channel3.disconnect();
    }
    catch (Exception e) {
      e.printStackTrace();
      Assert.assertTrue(false);
    }
  }

  private void checkBlobId(Coordinator coordinator, BlobId blobId, byte[] data) throws CoordinatorException, IOException {
    BlobOutput output = coordinator.getBlob(blobId.toString());
    Assert.assertEquals(output.getSize(), 1000);
    byte[] dataOutputStream = new byte[(int)output.getSize()];
    output.getStream().read(dataOutputStream);
    Assert.assertArrayEquals(dataOutputStream, data);
  }

  private void checkBlobContent(BlobId blobId, BlockingChannel channel, byte[] dataToCheck)
          throws IOException, MessageFormatException {
    ArrayList<BlobId> listIds = new ArrayList<BlobId>();
    listIds.add(blobId);
    GetRequest getRequest3 = new GetRequest(1, "clientid2", MessageFormatFlags.Blob, blobId.getPartition(), listIds);
    channel.send(getRequest3);
    InputStream stream = channel.receive();
    GetResponse resp = GetResponse.readFrom(new DataInputStream(stream), clusterMap);
    BlobOutput blobOutput = MessageFormatRecord.deserializeBlob(resp.getInputStream());
    byte[] blobout = new byte[(int)blobOutput.getSize()];
    int readsize = 0;
    while (readsize < blobOutput.getSize()) {
      readsize += blobOutput.getStream().read(blobout, readsize, (int)blobOutput.getSize() - readsize);
    }
    Assert.assertArrayEquals(blobout, dataToCheck);
  }

  private VerifiableProperties getCoordinatorProperties() {
    Properties properties = new Properties();
    properties.setProperty("coordinator.hostname", "localhost");
    properties.setProperty("coordinator.datacenter.name", "Datacenter");
    return new VerifiableProperties(properties);
  }

  public static void deleteFolderContent(File folder) {
    File[] files = folder.listFiles();
    if(files!=null) {
      for(File f: files) {
        if(f.isDirectory()) {
          deleteFolderContent(f);
        } else {
          f.delete();
        }
      }
    }
    folder.delete();
  }
}
package com.linkedin.metadata.entity;

import static com.linkedin.metadata.Constants.*;
import static com.linkedin.metadata.entity.EntityServiceTest.TEST_AUDIT_STAMP;
import static com.linkedin.metadata.telemetry.OpenTelemetryKeyConstants.EVENT_TYPE_ATTR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import com.codahale.metrics.Counter;
import com.datahub.util.RecordUtils;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.Status;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.DataTemplateUtil;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.dataset.DatasetProfile;
import com.linkedin.dataset.UpstreamLineage;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.identity.CorpUserInfo;
import com.linkedin.metadata.AspectGenerationUtils;
import com.linkedin.metadata.aspect.SystemAspect;
import com.linkedin.metadata.aspect.batch.AspectsBatch;
import com.linkedin.metadata.aspect.batch.ChangeMCP;
import com.linkedin.metadata.config.PreProcessHooks;
import com.linkedin.metadata.datahubusage.DataHubUsageEventType;
import com.linkedin.metadata.entity.ebean.EbeanAspectV2;
import com.linkedin.metadata.entity.ebean.EbeanSystemAspect;
import com.linkedin.metadata.entity.ebean.PartitionedStream;
import com.linkedin.metadata.entity.ebean.batch.AspectsBatchImpl;
import com.linkedin.metadata.entity.ebean.batch.ChangeItemImpl;
import com.linkedin.metadata.entity.ebean.batch.DeleteItemImpl;
import com.linkedin.metadata.entity.restoreindices.RestoreIndicesArgs;
import com.linkedin.metadata.entity.restoreindices.RestoreIndicesResult;
import com.linkedin.metadata.event.EventProducer;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.metadata.utils.SystemMetadataUtils;
import com.linkedin.metadata.utils.metrics.MetricUtils;
import com.linkedin.mxe.MetadataChangeLog;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.mxe.SystemMetadata;
import com.linkedin.util.Pair;
import io.datahubproject.metadata.context.OperationContext;
import io.datahubproject.metadata.context.SystemTelemetryContext;
import io.datahubproject.test.metadata.context.TestOperationContexts;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;
import jakarta.persistence.EntityNotFoundException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class EntityServiceImplTest {
  private final AuditStamp TEST_AUDIT_STAMP = AspectGenerationUtils.createAuditStamp();
  private final OperationContext opContext =
      TestOperationContexts.systemContextNoSearchAuthorization();
  private final EntityRegistry testEntityRegistry = opContext.getEntityRegistry();

  private static final Urn TEST_URN = UrnUtils.getUrn("urn:li:corpuser:EntityServiceImplTest");

  private EventProducer mockEventProducer;
  private Status oldAspect;
  private Status newAspect;
  private EntityServiceImpl entityService;
  private MetadataChangeProposal testMCP;
  private AspectDao mockAspectDao;

  @BeforeMethod
  public void setup() throws Exception {
    mockEventProducer = mock(EventProducer.class);
    mockAspectDao = mock(AspectDao.class);

    // Initialize common test objects
    entityService =
        new EntityServiceImpl(
            mock(AspectDao.class), mockEventProducer, false, mock(PreProcessHooks.class), 0, true);

    // Create test aspects
    oldAspect = new Status().setRemoved(false);
    newAspect = new Status().setRemoved(true);

    testMCP =
        new MetadataChangeProposal()
            .setEntityUrn(TEST_URN)
            .setEntityType(TEST_URN.getEntityType())
            .setAspectName(STATUS_ASPECT_NAME)
            .setAspect(GenericRecordUtils.serializeAspect(newAspect));

    when(mockEventProducer.produceMetadataChangeLog(
            any(OperationContext.class), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  @Test
  public void testApplyUpsertNoOp() throws Exception {
    // Set up initial system metadata
    SystemMetadata initialMetadata = new SystemMetadata();
    initialMetadata.setRunId("run-1");
    initialMetadata.setVersion("1");
    initialMetadata.setLastObserved(1000L);

    // Create initial aspect that will be stored in database
    CorpUserInfo originalInfo = AspectGenerationUtils.createCorpUserInfo("test@test.com");
    EbeanAspectV2 databaseAspectV2 =
        new EbeanAspectV2(
            "urn:li:corpuser:test", // urn
            "corpUserInfo", // aspect name
            0L, // version
            RecordUtils.toJsonString(originalInfo), // metadata
            new Timestamp(TEST_AUDIT_STAMP.getTime()), // createdOn
            TEST_AUDIT_STAMP.getActor().toString(), // createdBy
            null, // createdFor
            RecordUtils.toJsonString(initialMetadata) // systemMetadata
            );

    // Create the latest aspect that includes database reference
    SystemAspect latestAspect =
        EbeanSystemAspect.builder().forUpdate(databaseAspectV2, testEntityRegistry);

    // Create change with same content but updated metadata
    SystemMetadata newMetadata = new SystemMetadata();
    newMetadata.setRunId("run-2");
    newMetadata.setLastObserved(2000L);

    ChangeMCP changeMCP =
        ChangeItemImpl.builder()
            .urn(UrnUtils.getUrn("urn:li:corpuser:test"))
            .aspectName("corpUserInfo")
            .recordTemplate(originalInfo.copy()) // Same content, different instance
            .systemMetadata(newMetadata)
            .auditStamp(TEST_AUDIT_STAMP)
            .nextAspectVersion(2)
            .build(opContext.getAspectRetriever());

    // Apply upsert
    SystemAspect result = EntityServiceImpl.applyUpsert(changeMCP, latestAspect);

    // Verify metadata was updated but content remained same
    assertEquals(changeMCP.getNextAspectVersion(), 1, "1 which is then incremented back to 2");
    assertEquals(result.getSystemMetadata().getVersion(), "1");
    assertEquals(initialMetadata.getVersion(), "1");
    assertEquals(latestAspect.getSystemMetadataVersion().get(), 1L);
    assertNull(newMetadata.getVersion());

    assertEquals(result.getSystemMetadata().getRunId(), "run-2");
    assertEquals(result.getSystemMetadata().getLastRunId(), "run-1");
    assertEquals(result.getSystemMetadata().getLastObserved(), 2000L);
    assertTrue(DataTemplateUtil.areEqual(result.getRecordTemplate(), originalInfo));
  }

  @Test
  public void testApplyUpsertUpdate() throws Exception {
    // Set up initial system metadata and aspect
    SystemMetadata initialMetadata = new SystemMetadata();
    initialMetadata.setRunId("run-1");
    initialMetadata.setVersion("1");

    // Create initial aspect that will be stored in database
    CorpUserInfo originalInfo = AspectGenerationUtils.createCorpUserInfo("test@test.com");
    EbeanAspectV2 databaseAspectV2 =
        new EbeanAspectV2(
            "urn:li:corpuser:test", // urn
            "corpUserInfo", // aspect name
            0L, // version
            RecordUtils.toJsonString(originalInfo), // metadata
            new Timestamp(TEST_AUDIT_STAMP.getTime()), // createdOn
            TEST_AUDIT_STAMP.getActor().toString(), // createdBy
            null, // createdFor
            RecordUtils.toJsonString(initialMetadata) // systemMetadata
            );

    // Create the latest aspect that includes database reference
    SystemAspect latestAspect =
        EbeanSystemAspect.builder().forUpdate(databaseAspectV2, testEntityRegistry);

    // Create change with different content
    CorpUserInfo newInfo = AspectGenerationUtils.createCorpUserInfo("new@test.com");
    SystemMetadata newMetadata = new SystemMetadata();
    newMetadata.setRunId("run-2");

    ChangeMCP changeMCP =
        ChangeItemImpl.builder()
            .urn(UrnUtils.getUrn("urn:li:corpuser:test"))
            .aspectName("corpUserInfo")
            .recordTemplate(newInfo)
            .systemMetadata(newMetadata)
            .auditStamp(TEST_AUDIT_STAMP)
            .nextAspectVersion(Long.valueOf(initialMetadata.getVersion()) + 1)
            .build(opContext.getAspectRetriever());

    // Apply upsert
    SystemAspect result = EntityServiceImpl.applyUpsert(changeMCP, latestAspect);

    // Verify both metadata and content were updated
    assertEquals(changeMCP.getNextAspectVersion(), 2, "Expected acceptance of proposed version");
    assertEquals(result.getSystemMetadata().getVersion(), "2");
    assertEquals(initialMetadata.getVersion(), "1");
    assertEquals(latestAspect.getSystemMetadataVersion().get(), 2L);
    assertNull(newMetadata.getVersion());

    assertEquals(result.getSystemMetadata().getRunId(), "run-2");
    assertEquals(result.getSystemMetadata().getLastRunId(), "run-1");
    assertTrue(DataTemplateUtil.areEqual(result.getRecordTemplate(), newInfo));
    assertEquals(result.getSystemMetadataVersion(), Optional.of(2L));

    // Verify previous aspect was set in changeMCP
    assertNotNull(changeMCP.getPreviousSystemAspect());
    assertTrue(
        DataTemplateUtil.areEqual(
            changeMCP.getPreviousSystemAspect().getRecordTemplate(), originalInfo));
  }

  @Test
  public void testApplyUpsertInsert() throws Exception {
    // Create new aspect via ChangeMCP
    SystemMetadata newMetadata = new SystemMetadata();
    newMetadata.setRunId("run-1");

    CorpUserInfo newInfo = AspectGenerationUtils.createCorpUserInfo("test@test.com");

    // For insert case, there is no existing aspect in the database
    // so latestAspect should be null

    ChangeMCP changeMCP =
        ChangeItemImpl.builder()
            .urn(UrnUtils.getUrn("urn:li:corpuser:test"))
            .aspectName("corpUserInfo")
            .recordTemplate(newInfo)
            .systemMetadata(newMetadata)
            .auditStamp(TEST_AUDIT_STAMP)
            .nextAspectVersion(1)
            .build(opContext.getAspectRetriever());

    // No existing aspect
    SystemAspect result = EntityServiceImpl.applyUpsert(changeMCP, null);

    // Verify new aspect was created correctly
    assertNotNull(result);

    assertEquals(changeMCP.getNextAspectVersion(), 1, "Expected 1 since its initial");
    assertEquals(result.getSystemMetadata().getVersion(), "1");
    assertNull(newMetadata.getVersion());

    assertEquals(result.getSystemMetadata().getRunId(), "run-1");
    assertEquals(result.getSystemMetadata().getLastRunId(), "no-run-id-provided");
    assertTrue(DataTemplateUtil.areEqual(result.getRecordTemplate(), newInfo));

    // Additional verifications
    assertNotNull(result.getCreatedOn());
    assertEquals(result.getCreatedBy(), TEST_AUDIT_STAMP.getActor().toString());
  }

  @Test
  public void testApplyUpsertMultiInsert() throws Exception {
    // Create new aspect via ChangeMCP
    SystemMetadata newMetadata = new SystemMetadata();
    newMetadata.setRunId("run-1");

    CorpUserInfo newInfo1 = AspectGenerationUtils.createCorpUserInfo("test1@test.com");
    CorpUserInfo newInfo2 = AspectGenerationUtils.createCorpUserInfo("test2@test.com");

    // For insert case, there is no existing aspect in the database
    // so latestAspect should be null
    ChangeMCP changeMCP1 =
        ChangeItemImpl.builder()
            .urn(UrnUtils.getUrn("urn:li:corpuser:test"))
            .aspectName("corpUserInfo")
            .recordTemplate(newInfo1)
            .systemMetadata(newMetadata)
            .auditStamp(TEST_AUDIT_STAMP)
            .nextAspectVersion(1)
            .build(opContext.getAspectRetriever());

    // No existing aspect
    SystemAspect result1 = EntityServiceImpl.applyUpsert(changeMCP1, null);

    // Change 1
    assertNotNull(result1);
    assertEquals(changeMCP1.getNextAspectVersion(), 1, "Expected 0 since its initial");
    assertEquals(result1.getSystemMetadata().getVersion(), "1");
    assertNull(newMetadata.getVersion());
    assertEquals(result1.getSystemMetadata().getRunId(), "run-1");
    assertEquals(result1.getSystemMetadata().getLastRunId(), "no-run-id-provided");
    assertTrue(DataTemplateUtil.areEqual(result1.getRecordTemplate(), newInfo1));
    assertNotNull(result1.getCreatedOn());
    assertEquals(result1.getCreatedBy(), TEST_AUDIT_STAMP.getActor().toString());
    assertTrue(result1.getDatabaseAspect().isEmpty());

    ChangeMCP changeMCP2 =
        ChangeItemImpl.builder()
            .urn(UrnUtils.getUrn("urn:li:corpuser:test"))
            .aspectName("corpUserInfo")
            .recordTemplate(newInfo2)
            .systemMetadata(newMetadata)
            .auditStamp(TEST_AUDIT_STAMP)
            .nextAspectVersion(changeMCP1.getNextAspectVersion() + 1)
            .build(opContext.getAspectRetriever());

    SystemAspect result2 =
        EntityServiceImpl.applyUpsert(changeMCP2, result1); // pass previous as latest

    // Change 2
    assertNotNull(result2);
    assertEquals(changeMCP2.getNextAspectVersion(), 2, "Expected 2");
    assertEquals(result2.getSystemMetadata().getVersion(), "2");
    assertNull(newMetadata.getVersion());
    assertEquals(result2.getSystemMetadata().getRunId(), "run-1");
    assertEquals(result2.getSystemMetadata().getLastRunId(), "no-run-id-provided");
    assertTrue(DataTemplateUtil.areEqual(result2.getRecordTemplate(), newInfo2));
    assertNotNull(result2.getCreatedOn());
    assertEquals(result2.getCreatedBy(), TEST_AUDIT_STAMP.getActor().toString());
  }

  @Test
  public void testApplyUpsertNullVersionException() {
    // Set up initial system metadata with null version
    SystemMetadata initialMetadata = new SystemMetadata();
    initialMetadata.setRunId("run-1");

    // Create initial aspect that will be stored in database
    CorpUserInfo originalInfo = AspectGenerationUtils.createCorpUserInfo("test@test.com");
    EbeanAspectV2 databaseAspectV2 =
        new EbeanAspectV2(
            "urn:li:corpuser:test",
            "corpUserInfo",
            0L,
            RecordUtils.toJsonString(originalInfo),
            new Timestamp(TEST_AUDIT_STAMP.getTime()),
            TEST_AUDIT_STAMP.getActor().toString(),
            null,
            RecordUtils.toJsonString(initialMetadata));

    // Create the latest aspect that includes database reference
    SystemAspect latestAspect =
        EbeanSystemAspect.builder().forUpdate(databaseAspectV2, testEntityRegistry);

    // Create change with different content to trigger update path
    SystemMetadata newMetadata = new SystemMetadata();
    newMetadata.setRunId("run-2");

    ChangeMCP changeMCP =
        ChangeItemImpl.builder()
            .urn(UrnUtils.getUrn("urn:li:corpuser:test"))
            .aspectName("corpUserInfo")
            .recordTemplate(originalInfo)
            .systemMetadata(newMetadata)
            .auditStamp(TEST_AUDIT_STAMP)
            .nextAspectVersion(1L)
            .build(opContext.getAspectRetriever());

    SystemAspect upsert = EntityServiceImpl.applyUpsert(changeMCP, latestAspect);
    assertEquals(upsert.getSystemMetadataVersion(), Optional.of(1L));
    assertEquals(upsert.getVersion(), 0);
    assertEquals(changeMCP.getNextAspectVersion(), 1);
    assertEquals(changeMCP.getSystemMetadata().getVersion(), "1");
  }

  @Test
  public void testNoMCLWhenSystemMetadataIsNoOp() {
    // Arrange
    SystemMetadata systemMetadata = SystemMetadataUtils.createDefaultSystemMetadata();
    SystemMetadataUtils.setNoOp(systemMetadata, true); // Makes it a no-op

    // Act
    Optional<Pair<Future<?>, Boolean>> result =
        entityService.conditionallyProduceMCLAsync(
            opContext,
            oldAspect,
            null, // oldSystemMetadata
            newAspect,
            systemMetadata,
            testMCP,
            TEST_URN,
            TEST_AUDIT_STAMP,
            opContext
                .getEntityRegistry()
                .getEntitySpec(TEST_URN.getEntityType())
                .getAspectSpec(STATUS_ASPECT_NAME));

    // Assert
    assertFalse(result.isPresent(), "Should not produce MCL when system metadata is no-op");
    verify(mockEventProducer, never()).produceMetadataChangeLog(any(), any(), any());
  }

  @Test
  public void testNoMCLWhenAspectsAreEqual() {
    // Arrange
    RecordTemplate sameAspect = newAspect;

    // Act
    Optional<Pair<Future<?>, Boolean>> result =
        entityService.conditionallyProduceMCLAsync(
            opContext,
            sameAspect,
            null, // oldSystemMetadata
            sameAspect,
            SystemMetadataUtils.createDefaultSystemMetadata(),
            testMCP,
            TEST_URN,
            TEST_AUDIT_STAMP,
            opContext
                .getEntityRegistry()
                .getEntitySpec(TEST_URN.getEntityType())
                .getAspectSpec(STATUS_ASPECT_NAME));

    // Assert
    assertFalse(result.isPresent(), "Should not produce MCL when aspects are equal");
    verify(mockEventProducer, never()).produceMetadataChangeLog(any(), any(), any());
  }

  @Test
  public void testProducesMCLWhenChangesExist() {
    // Arrange
    SystemMetadata systemMetadata = SystemMetadataUtils.createDefaultSystemMetadata();
    SystemMetadataUtils.setNoOp(systemMetadata, false); // Makes it not a no-op

    // Act
    Optional<Pair<Future<?>, Boolean>> result =
        entityService.conditionallyProduceMCLAsync(
            opContext,
            oldAspect,
            null, // oldSystemMetadata
            newAspect,
            systemMetadata,
            testMCP,
            TEST_URN,
            TEST_AUDIT_STAMP,
            opContext
                .getEntityRegistry()
                .getEntitySpec(TEST_URN.getEntityType())
                .getAspectSpec(STATUS_ASPECT_NAME));

    // Assert
    assertTrue(result.isPresent(), "Should produce MCL when changes exist");
    verify(mockEventProducer, times(1))
        .produceMetadataChangeLog(any(OperationContext.class), any(), any(), any());
  }

  @Test
  public void testAlwaysEmitChangeLogFlag() {
    // Arrange
    entityService =
        new EntityServiceImpl(
            mock(AspectDao.class),
            mockEventProducer,
            true, // alwaysEmitChangeLog set to true
            mock(PreProcessHooks.class),
            0,
            true);

    RecordTemplate sameAspect = newAspect;

    // Act
    Optional<Pair<Future<?>, Boolean>> result =
        entityService.conditionallyProduceMCLAsync(
            opContext,
            sameAspect,
            null, // oldSystemMetadata
            sameAspect, // Same aspect
            SystemMetadataUtils.createDefaultSystemMetadata(),
            testMCP,
            TEST_URN,
            TEST_AUDIT_STAMP,
            opContext
                .getEntityRegistry()
                .getEntitySpec(TEST_URN.getEntityType())
                .getAspectSpec(STATUS_ASPECT_NAME));

    // Assert
    assertTrue(
        result.isPresent(),
        "Should produce MCL when alwaysEmitChangeLog is true, regardless of no-op status");
    verify(mockEventProducer, times(1))
        .produceMetadataChangeLog(any(OperationContext.class), any(), any(), any());
  }

  @Test
  public void testAspectWithLineageRelationship() {
    // Arrange
    Urn datasetUrn =
        UrnUtils.getUrn(
            "urn:li:dataset:(urn:li:dataPlatform:test,testAspectWithLineageRelationship,PROD)");
    UpstreamLineage sameLineageAspect = new UpstreamLineage();
    MetadataChangeProposal datasetMCP =
        new MetadataChangeProposal()
            .setEntityUrn(datasetUrn)
            .setEntityType(datasetUrn.getEntityType())
            .setAspectName(UPSTREAM_LINEAGE_ASPECT_NAME)
            .setAspect(GenericRecordUtils.serializeAspect(sameLineageAspect));

    // Act
    Optional<Pair<Future<?>, Boolean>> result =
        entityService.conditionallyProduceMCLAsync(
            opContext,
            sameLineageAspect,
            null, // oldSystemMetadata
            sameLineageAspect, // Same aspect
            SystemMetadataUtils.createDefaultSystemMetadata(),
            datasetMCP,
            datasetUrn,
            TEST_AUDIT_STAMP,
            opContext
                .getEntityRegistry()
                .getEntitySpec(datasetUrn.getEntityType())
                .getAspectSpec(UPSTREAM_LINEAGE_ASPECT_NAME));

    // Assert
    assertTrue(
        result.isPresent(),
        "Should produce MCL when aspect has lineage relationship, regardless of no-op status");
    verify(mockEventProducer, times(1))
        .produceMetadataChangeLog(any(OperationContext.class), any(), any(), any());
  }

  @Test
  public void testIngestTimeseriesProposal() {
    // Create a spy of the EntityServiceImpl to track method calls
    EntityServiceImpl entityServiceSpy = spy(entityService);

    Urn timeseriesUrn =
        UrnUtils.getUrn("urn:li:dataset:(urn:li:dataPlatform:test,timeseriesTest,PROD)");
    DatasetProfile datasetProfile = new DatasetProfile();
    datasetProfile.setRowCount(1000);
    datasetProfile.setColumnCount(15);
    datasetProfile.setTimestampMillis(0L);

    // Create a mock AspectsBatch with timeseries aspects
    AspectsBatch mockBatch =
        AspectsBatchImpl.builder()
            .retrieverContext(opContext.getRetrieverContext())
            .items(
                List.of(
                    ChangeItemImpl.builder()
                        .urn(timeseriesUrn)
                        .aspectName(DATASET_PROFILE_ASPECT_NAME)
                        .recordTemplate(datasetProfile)
                        .changeType(ChangeType.UPSERT)
                        .auditStamp(TEST_AUDIT_STAMP)
                        .build(opContext.getAspectRetriever()),
                    ChangeItemImpl.builder()
                        .urn(timeseriesUrn)
                        .aspectName(DATASET_PROFILE_ASPECT_NAME)
                        .recordTemplate(datasetProfile)
                        .changeType(ChangeType.UPSERT)
                        .auditStamp(TEST_AUDIT_STAMP)
                        .build(opContext.getAspectRetriever())))
            .build(opContext);

    // Test case 1: async = true path
    // Arrange
    doReturn(Stream.empty())
        .when(entityServiceSpy)
        .ingestProposalAsync(any(OperationContext.class), any(AspectsBatch.class));

    // Act
    entityServiceSpy.ingestTimeseriesProposal(opContext, mockBatch, true);

    // Verify
    verify(entityServiceSpy, times(1))
        .ingestProposalAsync(any(OperationContext.class), any(AspectsBatch.class));
    verify(entityServiceSpy, never())
        .ingestProposalSync(any(OperationContext.class), any(AspectsBatch.class));

    // Test case 2: async = false path
    // Arrange
    org.mockito.Mockito.reset(entityServiceSpy);
    doReturn(Stream.empty())
        .when(entityServiceSpy)
        .ingestProposalSync(any(OperationContext.class), any(AspectsBatch.class));

    // Act
    entityServiceSpy.ingestTimeseriesProposal(opContext, mockBatch, false);

    // Verify
    verify(entityServiceSpy, never())
        .ingestProposalAsync(any(OperationContext.class), any(AspectsBatch.class));
    verify(entityServiceSpy, times(1))
        .ingestProposalSync(any(OperationContext.class), any(AspectsBatch.class));
  }

  @Test
  public void testIngestTimeseriesProposalUnsupported() {
    // Create a spy of the EntityServiceImpl to track method calls
    EntityServiceImpl entityServiceSpy = spy(entityService);

    Urn timeseriesUrn =
        UrnUtils.getUrn("urn:li:dataset:(urn:li:dataPlatform:test,timeseriesUnsupportedTest,PROD)");

    // Create a mock AspectsBatch with timeseries aspects
    AspectsBatch mockBatch =
        AspectsBatchImpl.builder()
            .retrieverContext(opContext.getRetrieverContext())
            .items(
                List.of(
                    DeleteItemImpl.builder()
                        .urn(timeseriesUrn)
                        .aspectName(DATASET_PROFILE_ASPECT_NAME)
                        .auditStamp(TEST_AUDIT_STAMP)
                        .build(opContext.getAspectRetriever()),
                    DeleteItemImpl.builder()
                        .urn(timeseriesUrn)
                        .aspectName(DATASET_PROFILE_ASPECT_NAME)
                        .auditStamp(TEST_AUDIT_STAMP)
                        .build(opContext.getAspectRetriever())))
            .build(opContext);

    try {
      entityServiceSpy.ingestTimeseriesProposal(opContext, mockBatch, true);
      fail("Should throw UnsupportedOperationException for non-UPSERT change types");
    } catch (UnsupportedOperationException e) {
      // Expected
    }
  }

  /**
   * Tests an end-to-end scenario with createDefaultAspects=true, ensuring that both the aspects are
   * restored to the index and default aspects are created.
   */
  @Test
  public void testRestoreIndicesEndToEndWithDefaultAspects() throws Exception {
    // Setup mock AspectDao
    AspectDao mockAspectDao = mock(AspectDao.class);
    PartitionedStream<EbeanAspectV2> mockStream = mock(PartitionedStream.class);

    // Create test aspects
    List<EbeanAspectV2> batch = new ArrayList<>();

    // Dataset aspect
    EbeanAspectV2 datasetAspect =
        new EbeanAspectV2(
            "urn:li:dataset:(urn:li:dataPlatform:test,defaultAspectsTest,PROD)",
            STATUS_ASPECT_NAME,
            0L,
            RecordUtils.toJsonString(new Status().setRemoved(false)),
            new Timestamp(System.currentTimeMillis()),
            TEST_AUDIT_STAMP.getActor().toString(),
            null,
            RecordUtils.toJsonString(SystemMetadataUtils.createDefaultSystemMetadata()));
    batch.add(datasetAspect);

    // Setup mock stream
    when(mockStream.partition(anyInt())).thenReturn(Stream.of(batch.stream()));
    when(mockAspectDao.streamAspectBatches(any())).thenReturn(mockStream);

    // Setup mock EventProducer
    EventProducer mockEventProducer = mock(EventProducer.class);
    when(mockEventProducer.produceMetadataChangeLog(
            any(OperationContext.class), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Create EntityServiceImpl with mocks
    EntityServiceImpl entityServiceSpy =
        spy(
            new EntityServiceImpl(
                mockAspectDao, mockEventProducer, false, mock(PreProcessHooks.class), 0, true));

    // Mock ingestProposalSync to capture default aspects
    ArgumentCaptor<AspectsBatch> batchCaptor = ArgumentCaptor.forClass(AspectsBatch.class);
    doReturn(Stream.empty())
        .when(entityServiceSpy)
        .ingestProposalSync(any(OperationContext.class), batchCaptor.capture());

    // Create RestoreIndicesArgs with createDefaultAspects set to true
    RestoreIndicesArgs args =
        new RestoreIndicesArgs()
            .start(0)
            .limit(100)
            .batchSize(50)
            .batchDelayMs(0L)
            .createDefaultAspects(true); // Explicitly set to true

    // Execute the method under test
    List<RestoreIndicesResult> results =
        entityServiceSpy.restoreIndices(opContext, args, message -> {});

    // Verify results
    assertNotNull(results);
    assertEquals(1, results.size());

    // Verify MCL production
    verify(mockEventProducer)
        .produceMetadataChangeLog(any(OperationContext.class), any(), any(), any());

    // Verify default aspect creation was attempted
    verify(entityServiceSpy).ingestProposalSync(any(OperationContext.class), any());

    // Verify the captured batch contains aspects
    AspectsBatch capturedBatch = batchCaptor.getValue();
    assertNotNull(capturedBatch);
    assertFalse(capturedBatch.getItems().isEmpty());

    // Verify the defaultAspectsCreated count in results
    assertTrue(results.get(0).defaultAspectsCreated >= 0);
  }

  /**
   * Tests an end-to-end scenario with createDefaultAspects=false, ensuring that only aspects are
   * restored to the index without creating default aspects.
   */
  @Test
  public void testRestoreIndicesEndToEndWithoutDefaultAspects() throws Exception {
    // Setup mock AspectDao
    AspectDao mockAspectDao = mock(AspectDao.class);
    PartitionedStream<EbeanAspectV2> mockStream = mock(PartitionedStream.class);

    // Create test aspects
    List<EbeanAspectV2> batch = new ArrayList<>();

    // Dataset aspect
    EbeanAspectV2 datasetAspect =
        new EbeanAspectV2(
            "urn:li:dataset:(urn:li:dataPlatform:test,defaultAspectsTest,PROD)",
            STATUS_ASPECT_NAME,
            0L,
            RecordUtils.toJsonString(new Status().setRemoved(false)),
            new Timestamp(System.currentTimeMillis()),
            TEST_AUDIT_STAMP.getActor().toString(),
            null,
            RecordUtils.toJsonString(SystemMetadataUtils.createDefaultSystemMetadata()));
    batch.add(datasetAspect);

    // Setup mock stream
    when(mockStream.partition(anyInt())).thenReturn(Stream.of(batch.stream()));
    when(mockAspectDao.streamAspectBatches(any())).thenReturn(mockStream);

    // Setup mock EventProducer
    EventProducer mockEventProducer = mock(EventProducer.class);
    when(mockEventProducer.produceMetadataChangeLog(
            any(OperationContext.class), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Create EntityServiceImpl with mocks
    EntityServiceImpl entityServiceSpy =
        spy(
            new EntityServiceImpl(
                mockAspectDao, mockEventProducer, false, mock(PreProcessHooks.class), 0, true));

    // Simply stub the method without capturing
    doReturn(Stream.empty())
        .when(entityServiceSpy)
        .ingestProposalSync(any(OperationContext.class), any(AspectsBatch.class));

    // Create RestoreIndicesArgs with createDefaultAspects set to false
    RestoreIndicesArgs args =
        new RestoreIndicesArgs()
            .start(0)
            .limit(100)
            .batchSize(50)
            .batchDelayMs(0L)
            .createDefaultAspects(false); // Explicitly set to false

    // Execute the method under test
    List<RestoreIndicesResult> results =
        entityServiceSpy.restoreIndices(opContext, args, message -> {});

    // Verify results
    assertNotNull(results);
    assertEquals(1, results.size());

    // Verify MCL production
    verify(mockEventProducer)
        .produceMetadataChangeLog(any(OperationContext.class), any(), any(), any());

    // Verify default aspect creation was never attempted
    verify(entityServiceSpy, never()).ingestProposalSync(any(OperationContext.class), any());

    // Don't try to use the capturedBatch since the method should never be called
    // Instead, verify the defaultAspectsCreated count in results is 0
    assertEquals(0, results.get(0).defaultAspectsCreated);
  }

  /**
   * Tests the continuation behavior of restoreIndices when an exception occurs. It should continue
   * processing other aspects even if one fails.
   */
  @Test
  public void testRestoreIndicesContinuationOnException() throws Exception {
    // Setup mock AspectDao
    AspectDao mockAspectDao = mock(AspectDao.class);
    PartitionedStream<EbeanAspectV2> mockStream = mock(PartitionedStream.class);

    // Create test aspects
    List<EbeanAspectV2> batch = new ArrayList<>();

    // First aspect (will succeed)
    EbeanAspectV2 successAspect =
        new EbeanAspectV2(
            "urn:li:dataset:(urn:li:dataPlatform:test,success,PROD)",
            STATUS_ASPECT_NAME,
            0L,
            RecordUtils.toJsonString(new Status().setRemoved(false)),
            new Timestamp(System.currentTimeMillis()),
            TEST_AUDIT_STAMP.getActor().toString(),
            null,
            RecordUtils.toJsonString(SystemMetadataUtils.createDefaultSystemMetadata()));
    batch.add(successAspect);

    // Second aspect (will fail)
    EbeanAspectV2 failAspect =
        new EbeanAspectV2(
            "urn:li:dataset:(urn:li:dataPlatform:test,fail,PROD)",
            STATUS_ASPECT_NAME,
            0L,
            "INVALID_JSON", // This will cause deserialization to fail
            new Timestamp(System.currentTimeMillis()),
            TEST_AUDIT_STAMP.getActor().toString(),
            null,
            RecordUtils.toJsonString(SystemMetadataUtils.createDefaultSystemMetadata()));
    batch.add(failAspect);

    // Third aspect (will succeed)
    EbeanAspectV2 anotherSuccessAspect =
        new EbeanAspectV2(
            "urn:li:dataset:(urn:li:dataPlatform:test,anotherSuccess,PROD)",
            STATUS_ASPECT_NAME,
            0L,
            RecordUtils.toJsonString(new Status().setRemoved(false)),
            new Timestamp(System.currentTimeMillis()),
            TEST_AUDIT_STAMP.getActor().toString(),
            null,
            RecordUtils.toJsonString(SystemMetadataUtils.createDefaultSystemMetadata()));
    batch.add(anotherSuccessAspect);

    // Setup mock stream to create two separate batches
    // This is the key change - we need to separate the failing aspect into its own batch
    when(mockStream.partition(anyInt()))
        .thenReturn(
            Stream.of(
                // First batch with success aspect
                Stream.of(successAspect),
                // Second batch with failing aspect
                Stream.of(failAspect),
                // Third batch with another success aspect
                Stream.of(anotherSuccessAspect)));

    when(mockAspectDao.streamAspectBatches(any())).thenReturn(mockStream);

    // Setup mock EventProducer
    EventProducer mockEventProducer = mock(EventProducer.class);
    when(mockEventProducer.produceMetadataChangeLog(
            any(OperationContext.class),
            argThat(
                urn ->
                    urn.toString()
                        .contains("success")), // Only succeed for URNs containing "success"
            any(),
            any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Create EntityServiceImpl with mocks
    EntityServiceImpl entityService =
        new EntityServiceImpl(
            mockAspectDao, mockEventProducer, false, mock(PreProcessHooks.class), 0, true);

    // Create RestoreIndicesArgs
    RestoreIndicesArgs args =
        new RestoreIndicesArgs()
            .start(0)
            .limit(100)
            .batchSize(1) // Process one aspect at a time
            .batchDelayMs(0L)
            .createDefaultAspects(false); // Avoid additional complexity

    // Execute the method under test
    List<RestoreIndicesResult> results =
        entityService.restoreIndices(opContext, args, message -> {});

    // Verify results
    assertNotNull(results);
    // We should get exactly 2 results - one for each successful batch
    assertEquals(2, results.size());

    // We expect to see rowsMigrated = 1 in each successful result
    assertEquals(1, results.get(0).rowsMigrated);
    assertEquals(1, results.get(1).rowsMigrated);

    // The failing aspect should not generate a result at all, as the implementation
    // filters out null results (batches that throw exceptions)

    // Verify total calls to produceMetadataChangeLog (one for each successful aspect)
    verify(mockEventProducer, times(2))
        .produceMetadataChangeLog(any(OperationContext.class), any(), any(), any());
  }

  @Test
  public void testDeleteAspectWithoutMCL_EntityNotFoundException() {
    // Mock AspectDao
    AspectDao mockAspectDao = mock(AspectDao.class);
    EventProducer mockEventProducer = mock(EventProducer.class);

    // Create entity service with mocked components
    EntityServiceImpl entityService =
        new EntityServiceImpl(
            mockAspectDao, mockEventProducer, false, mock(PreProcessHooks.class), 0, true);

    // Create test inputs
    Urn testUrn = UrnUtils.getUrn("urn:li:corpuser:test");
    String aspectName = "status";
    Map<String, String> conditions = Collections.singletonMap("runId", "test-run-id");

    // This is the key part - we need to make runInTransactionWithRetry actually execute
    // the function that's passed to it, so the getLatestAspect call inside it will throw
    doAnswer(
            invocation -> {
              Function<TransactionContext, TransactionResult<RollbackResult>> function =
                  invocation.getArgument(0);
              TransactionContext txContext = TransactionContext.empty(3);

              // Before calling the function, set up the mock that will throw inside it
              when(mockAspectDao.getLatestAspect(
                      any(OperationContext.class),
                      eq(testUrn.toString()),
                      eq(aspectName),
                      eq(false)))
                  .thenThrow(
                      new EntityNotFoundException("Lazy loading failed - Bean has been deleted"));

              try {
                // This will execute the transaction function, which should call getLatestAspect
                // and catch the exception
                return function.apply(txContext).getResults();
              } catch (Exception e) {
                // If any exception escapes, it means our test is wrong
                fail("Exception should be caught inside transaction function: " + e.getMessage());
                return Optional.empty();
              }
            })
        .when(mockAspectDao)
        .runInTransactionWithRetry(any(), anyInt());

    // Create a counter mock
    Counter mockCounter = mock(Counter.class);

    // Mock the metricUtils
    MetricUtils metricUtils = mock(MetricUtils.class);
    OperationContext testContext =
        opContext.toBuilder()
            .systemTelemetryContext(
                SystemTelemetryContext.builder()
                    .metricUtils(metricUtils)
                    .tracer(mock(Tracer.class))
                    .build())
            .build(opContext.getSystemActorContext().getAuthentication(), false);

    // Execute the method
    RollbackResult result =
        entityService.deleteAspectWithoutMCL(
            testContext, testUrn.toString(), aspectName, conditions, true);

    // Verify result is null
    assertNull(result, "Result should be null when EntityNotFoundException is caught");

    // Verify metric was incremented
    verify(metricUtils, times(1))
        .increment(eq(EntityServiceImpl.class), eq("delete_nonexisting"), eq(1d));
  }

  @Test
  public void testRestoreIndicesWithNullSystemMetadata() {
    // Setup mock AspectDao
    AspectDao mockAspectDao = mock(AspectDao.class);
    PartitionedStream<EbeanAspectV2> mockStream = mock(PartitionedStream.class);

    // Create test aspects
    List<EbeanAspectV2> batch = new ArrayList<>();

    // Create an aspect with null system metadata
    Urn testUrn =
        UrnUtils.getUrn("urn:li:dataset:(urn:li:dataPlatform:test,nullMetadataTest,PROD)");
    Status testStatus = new Status().setRemoved(false);

    EbeanAspectV2 aspectWithNullMetadata =
        new EbeanAspectV2(
            testUrn.toString(),
            STATUS_ASPECT_NAME,
            0L,
            RecordUtils.toJsonString(testStatus),
            new Timestamp(System.currentTimeMillis()),
            TEST_AUDIT_STAMP.getActor().toString(),
            null,
            null // Explicitly set systemMetadata to null
            );
    batch.add(aspectWithNullMetadata);

    // Setup mock stream
    when(mockStream.partition(anyInt())).thenReturn(Stream.of(batch.stream()));
    when(mockAspectDao.streamAspectBatches(any())).thenReturn(mockStream);

    // Setup mock EventProducer
    EventProducer mockEventProducer = mock(EventProducer.class);
    when(mockEventProducer.produceMetadataChangeLog(
            any(OperationContext.class), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    // Create EntityServiceImpl with mocks
    EntityServiceImpl entityServiceSpy =
        spy(
            new EntityServiceImpl(
                mockAspectDao, mockEventProducer, false, mock(PreProcessHooks.class), 0, true));

    // Create RestoreIndicesArgs
    RestoreIndicesArgs args =
        new RestoreIndicesArgs()
            .start(0)
            .limit(100)
            .batchSize(50)
            .batchDelayMs(0L)
            .createDefaultAspects(false);

    // Execute the method under test
    List<RestoreIndicesResult> results =
        entityServiceSpy.restoreIndices(opContext, args, message -> {});

    // Verify results
    assertNotNull(results);
    assertEquals(1, results.size());
    assertEquals(1, results.get(0).rowsMigrated);
  }

  @Test
  public void testMapAspectToUsageEvent() {
    // Test cases for create/update operations

    // Test ACCESS_TOKEN_KEY_ASPECT_NAME
    AttributeKey<String> eventTypeAttrKey = AttributeKey.stringKey(EVENT_TYPE_ATTR);
    AttributesBuilder attributesBuilder = Attributes.builder();
    MetadataChangeLog mcl = new MetadataChangeLog();
    mcl.setAspectName(ACCESS_TOKEN_KEY_ASPECT_NAME);
    mcl.setChangeType(ChangeType.UPSERT);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.CREATE_ACCESS_TOKEN_EVENT.getType());

    // Test INGESTION_SOURCE_KEY_ASPECT_NAME
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(INGESTION_SOURCE_KEY_ASPECT_NAME);
    mcl.setChangeType(ChangeType.CREATE);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.CREATE_INGESTION_SOURCE_EVENT.getType());

    // Test INGESTION_INFO_ASPECT_NAME
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(INGESTION_INFO_ASPECT_NAME);
    mcl.setChangeType(ChangeType.UPDATE);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.UPDATE_INGESTION_SOURCE_EVENT.getType());

    // Test DATAHUB_POLICY_KEY_ASPECT_NAME
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(DATAHUB_POLICY_KEY_ASPECT_NAME);
    mcl.setChangeType(ChangeType.CREATE_ENTITY);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.CREATE_POLICY_EVENT.getType());

    // Test DATAHUB_POLICY_INFO_ASPECT_NAME
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(DATAHUB_POLICY_INFO_ASPECT_NAME);
    mcl.setChangeType(ChangeType.PATCH);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.UPDATE_POLICY_EVENT.getType());

    // Test CORP_USER_KEY_ASPECT_NAME
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(CORP_USER_KEY_ASPECT_NAME);
    mcl.setChangeType(ChangeType.UPSERT);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.CREATE_USER_EVENT.getType());

    // Test CORP_USER_INFO_ASPECT_NAME (should map to UPDATE_USER_EVENT)
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(CORP_USER_INFO_ASPECT_NAME);
    mcl.setChangeType(ChangeType.UPSERT);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.UPDATE_USER_EVENT.getType());

    // Test GROUP_MEMBERSHIP_ASPECT_NAME (should map to UPDATE_USER_EVENT)
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(GROUP_MEMBERSHIP_ASPECT_NAME);
    mcl.setChangeType(ChangeType.UPSERT);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.UPDATE_USER_EVENT.getType());

    // Test default case for create/update
    attributesBuilder = Attributes.builder();
    mcl.setAspectName("unknownAspect");
    mcl.setChangeType(ChangeType.UPSERT);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.UPDATE_ASPECT_EVENT.getType());

    // Test DELETE operations

    // Test ACCESS_TOKEN_KEY_ASPECT_NAME delete
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(ACCESS_TOKEN_KEY_ASPECT_NAME);
    mcl.setChangeType(ChangeType.DELETE);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.REVOKE_ACCESS_TOKEN_EVENT.getType());

    // Test DATAHUB_POLICY_KEY_ASPECT_NAME delete
    attributesBuilder = Attributes.builder();
    mcl.setAspectName(DATAHUB_POLICY_KEY_ASPECT_NAME);
    mcl.setChangeType(ChangeType.DELETE);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.DELETE_POLICY_EVENT.getType());

    // Test default delete case
    attributesBuilder = Attributes.builder();
    mcl.setAspectName("unknownAspect");
    mcl.setChangeType(ChangeType.DELETE);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.DELETE_ENTITY_EVENT.getType());

    // Test other change types (should default to ENTITY_EVENT)
    attributesBuilder = Attributes.builder();
    mcl.setAspectName("someAspect");
    mcl.setChangeType(ChangeType.RESTATE);

    entityService.mapAspectToUsageEvent(attributesBuilder, mcl);
    assertEquals(
        attributesBuilder.build().get(eventTypeAttrKey),
        DataHubUsageEventType.ENTITY_EVENT.getType());
  }

  @Test
  public void testIngestAspectsWithDuplicates() throws Exception {
    // Create duplicate aspects
    // Create a batch with duplicate aspects (same urn and aspect name)
    SystemMetadata metadata = SystemMetadataUtils.createDefaultSystemMetadata();
    List<ChangeItemImpl> items =
        List.of(
            ChangeItemImpl.builder()
                .urn(TEST_URN)
                .aspectName(STATUS_ASPECT_NAME)
                .recordTemplate(new Status().setRemoved(false))
                .systemMetadata(metadata)
                .auditStamp(TEST_AUDIT_STAMP)
                .build(opContext.getAspectRetriever()),
            ChangeItemImpl.builder()
                .urn(TEST_URN)
                .aspectName(STATUS_ASPECT_NAME)
                .recordTemplate(new Status().setRemoved(true))
                .systemMetadata(metadata)
                .auditStamp(TEST_AUDIT_STAMP)
                .build(opContext.getAspectRetriever()),
            ChangeItemImpl.builder()
                .urn(TEST_URN)
                .aspectName(STATUS_ASPECT_NAME)
                .recordTemplate(new Status().setRemoved(false))
                .systemMetadata(metadata)
                .auditStamp(TEST_AUDIT_STAMP)
                .build(opContext.getAspectRetriever()));

    AspectsBatchImpl aspectsBatch =
        AspectsBatchImpl.builder()
            .retrieverContext(opContext.getRetrieverContext())
            .items(items)
            .build(opContext);
    assertTrue(aspectsBatch.containsDuplicateAspects(), "Expected duplicates.");

    // Mock metric utils to verify the increment call
    MetricUtils mockMetricUtils = mock(MetricUtils.class);
    OperationContext testContext =
        opContext.toBuilder()
            .systemTelemetryContext(
                SystemTelemetryContext.builder()
                    .tracer(SystemTelemetryContext.TEST.getTracer())
                    .metricUtils(mockMetricUtils)
                    .build())
            .build(opContext.getSystemActorContext().getAuthentication(), false);

    // Mock the transaction to return an empty result
    when(mockAspectDao.runInTransactionWithRetry(any(), any(), anyInt()))
        .thenReturn(
            Optional.of(
                IngestAspectsResult.builder()
                    .updateAspectResults(List.of(UpdateAspectResult.builder().build()))
                    .build()));

    // Execute
    entityService.ingestAspects(testContext, aspectsBatch, true, true);

    // Verify the metric was incremented
    verify(mockMetricUtils, times(1))
        .increment(eq(EntityServiceImpl.class), eq("batch_with_duplicate"), eq(1.0d));
  }
}

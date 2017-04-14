/*
 * Copyright © 2015-2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.metadata;

import co.cask.cdap.AllProgramsApp;
import co.cask.cdap.AppWithDataset;
import co.cask.cdap.WordCountApp;
import co.cask.cdap.WordCountMinusFlowApp;
import co.cask.cdap.api.Config;
import co.cask.cdap.api.data.format.FormatSpecification;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.app.program.ManifestFields;
import co.cask.cdap.client.util.RESTClient;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.metadata.dataset.MetadataDataset;
import co.cask.cdap.data2.metadata.system.DatasetSystemMetadataWriter;
import co.cask.cdap.data2.transaction.stream.StreamAdmin;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactRepository;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.ViewSpecification;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.ArtifactRange;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import co.cask.cdap.proto.metadata.MetadataRecord;
import co.cask.cdap.proto.metadata.MetadataScope;
import co.cask.cdap.proto.metadata.MetadataSearchResultRecord;
import co.cask.cdap.proto.metadata.MetadataSearchTargetType;
import co.cask.common.http.HttpRequest;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.gson.JsonObject;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import javax.annotation.Nullable;

/**
 * Tests for {@link MetadataHttpHandler}
 */
public class MetadataHttpHandlerTest extends MetadataTestBase {

  private final Id.Application application =
    Id.Application.from(Id.Namespace.DEFAULT, AppWithDataset.class.getSimpleName());
  private final Id.Artifact artifactId = Id.Artifact.from(Id.Namespace.DEFAULT, application.getId(), "1.0.0");
  private final Id.Program pingService = Id.Program.from(application, ProgramType.SERVICE, "PingService");
  private final Id.DatasetInstance myds = Id.DatasetInstance.from(Id.Namespace.DEFAULT, "myds");
  private final Id.Stream mystream = Id.Stream.from(Id.Namespace.DEFAULT, "mystream");
  private final Id.Stream.View myview = Id.Stream.View.from(mystream, "myview");
  private final Id.Application nonExistingApp = Id.Application.from("blah", AppWithDataset.class.getSimpleName());
  private final Id.Service nonExistingService = Id.Service.from(nonExistingApp, "PingService");
  private final Id.DatasetInstance nonExistingDataset = Id.DatasetInstance.from("blah", "myds");
  private final Id.Stream nonExistingStream = Id.Stream.from("blah", "mystream");
  private final Id.Stream.View nonExistingView = Id.Stream.View.from(nonExistingStream, "myView");
  private final Id.Artifact nonExistingArtifact = Id.Artifact.from(Id.Namespace.from("blah"), "art", "1.0.0");

  @Before
  public void before() throws Exception {
    Assert.assertEquals(200, addAppArtifact(artifactId, AppWithDataset.class).getStatusLine().getStatusCode());
    AppRequest<Config> appRequest = new AppRequest<>(
      new ArtifactSummary(artifactId.getName(), artifactId.getVersion().getVersion()));
    Assert.assertEquals(200, deploy(application, appRequest).getStatusLine().getStatusCode());
    FormatSpecification format = new FormatSpecification("csv", null, null);
    ViewSpecification viewSpec = new ViewSpecification(format, null);
    createOrUpdateView(myview, viewSpec);
  }

  @After
  public void after() throws Exception {
    deleteApp(application, 200);
    deleteArtifact(artifactId, 200);
  }

  @Test
  public void testProperties() throws Exception {
    // should fail because we haven't provided any metadata in the request
    addProperties(application, null, BadRequestException.class);
    String multiWordValue = "wow1 WoW2   -    WOW3 - wow4_woW5 wow6";
    Map<String, String> appProperties = ImmutableMap.of("aKey", "aValue", "multiword", multiWordValue);
    addProperties(application, appProperties);
    // should fail because we haven't provided any metadata in the request
    addProperties(pingService, null, BadRequestException.class);
    Map<String, String> serviceProperties = ImmutableMap.of("sKey", "sValue", "sK", "sV");
    addProperties(pingService, serviceProperties);
    // should fail because we haven't provided any metadata in the request
    addProperties(myds, null, BadRequestException.class);
    Map<String, String> datasetProperties = ImmutableMap.of("dKey", "dValue", "dK", "dV");
    addProperties(myds, datasetProperties);
    // should fail because we haven't provided any metadata in the request
    addProperties(mystream, null, BadRequestException.class);
    Map<String, String> streamProperties = ImmutableMap.of("stKey", "stValue", "stK", "stV", "multiword",
                                                           multiWordValue);
    addProperties(mystream, streamProperties);
    addProperties(myview, null, BadRequestException.class);
    Map<String, String> viewProperties = ImmutableMap.of("viewKey", "viewValue", "viewK", "viewV");
    addProperties(myview, viewProperties);
    // should fail because we haven't provided any metadata in the request
    addProperties(artifactId, null, BadRequestException.class);
    Map<String, String> artifactProperties = ImmutableMap.of("rKey", "rValue", "rK", "rV");
    addProperties(artifactId, artifactProperties);
    // retrieve properties and verify
    Map<String, String> properties = getProperties(application, MetadataScope.USER);
    Assert.assertEquals(appProperties, properties);
    properties = getProperties(pingService, MetadataScope.USER);
    Assert.assertEquals(serviceProperties, properties);
    properties = getProperties(myds, MetadataScope.USER);
    Assert.assertEquals(datasetProperties, properties);
    properties = getProperties(mystream, MetadataScope.USER);
    Assert.assertEquals(streamProperties, properties);
    properties = getProperties(myview, MetadataScope.USER);
    Assert.assertEquals(viewProperties, properties);
    properties = getProperties(artifactId, MetadataScope.USER);
    Assert.assertEquals(artifactProperties, properties);

    // test search for application
    Set<MetadataSearchResultRecord> expected = ImmutableSet.of(
      new MetadataSearchResultRecord(application)
    );
    Set<MetadataSearchResultRecord> searchProperties = searchMetadata(Id.Namespace.DEFAULT,
                                                                      "aKey:aValue",
                                                                      MetadataSearchTargetType.APP);
    Assert.assertEquals(expected, searchProperties);
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "multiword:wow1", MetadataSearchTargetType.APP);
    Assert.assertEquals(expected, searchProperties);
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "multiword:woW5", MetadataSearchTargetType.APP);
    Assert.assertEquals(expected, searchProperties);
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "WOW3", MetadataSearchTargetType.APP);
    Assert.assertEquals(expected, searchProperties);

    // test search for stream
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "stKey:stValue", MetadataSearchTargetType.STREAM);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(mystream)
    );
    Assert.assertEquals(expected, searchProperties);

    // test search for view with lowercase key value when metadata was stored in mixed case
    searchProperties = searchMetadata(Id.Namespace.DEFAULT,
                                      "viewkey:viewvalue", MetadataSearchTargetType.VIEW);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(myview)
    );
    Assert.assertEquals(expected, searchProperties);

    // test search for view with lowercase value when metadata was stored in mixed case
    searchProperties = searchMetadata(Id.Namespace.DEFAULT,
                                      "viewvalue", MetadataSearchTargetType.VIEW);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(myview)
    );
    Assert.assertEquals(expected, searchProperties);

    // test search for artifact
    searchProperties = searchMetadata(Id.Namespace.DEFAULT,
                                      "rKey:rValue", MetadataSearchTargetType.ARTIFACT);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(artifactId)
    );
    Assert.assertEquals(expected, searchProperties);

    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(application),
      new MetadataSearchResultRecord(mystream)
    );

    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "multiword:w*", MetadataSearchTargetType.ALL);
    Assert.assertEquals(2, searchProperties.size());
    Assert.assertEquals(expected, searchProperties);

    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "multiword:*", MetadataSearchTargetType.ALL);
    Assert.assertEquals(2, searchProperties.size());
    Assert.assertEquals(expected, searchProperties);

    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "wo*", MetadataSearchTargetType.ALL);
    Assert.assertEquals(2, searchProperties.size());
    Assert.assertEquals(expected, searchProperties);

    // test prefix search for service
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "sKey:s*", MetadataSearchTargetType.ALL);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(pingService)
    );
    Assert.assertEquals(expected, searchProperties);

    // search without any target param
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "sKey:s*", null);
    Assert.assertEquals(expected, searchProperties);

    // Should get empty
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "sKey:s", null);
    Assert.assertTrue(searchProperties.size() == 0);

    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "s", null);
    Assert.assertTrue(searchProperties.size() == 0);

    // search non-existent property should return empty set
    searchProperties = searchMetadata(Id.Namespace.DEFAULT, "NullKey:s*", null);
    Assert.assertEquals(ImmutableSet.<MetadataSearchResultRecord>of(), searchProperties);

    // search invalid ns should return empty set
    searchProperties = searchMetadata(Id.Namespace.from("invalidnamespace"), "sKey:s*", null);
    Assert.assertEquals(ImmutableSet.of(), searchProperties);

    // test removal
    removeProperties(application);
    Assert.assertTrue(getProperties(application, MetadataScope.USER).isEmpty());
    removeProperty(pingService, "sKey");
    removeProperty(pingService, "sK");
    Assert.assertTrue(getProperties(pingService, MetadataScope.USER).isEmpty());
    removeProperty(myds, "dKey");
    Assert.assertEquals(ImmutableMap.of("dK", "dV"), getProperties(myds, MetadataScope.USER));
    removeProperty(mystream, "stK");
    removeProperty(mystream, "stKey");
    Assert.assertEquals(ImmutableMap.of("multiword", multiWordValue), getProperties(mystream, MetadataScope.USER));
    removeProperty(myview, "viewK");
    Assert.assertEquals(ImmutableMap.of("viewKey", "viewValue"), getProperties(myview, MetadataScope.USER));
    // cleanup
    removeProperties(myview);
    removeProperties(application);
    removeProperties(pingService);
    removeProperties(myds);
    removeProperties(mystream);
    removeProperties(artifactId);
    Assert.assertTrue(getProperties(application, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getProperties(pingService, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getProperties(myds, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getProperties(mystream, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getProperties(myview, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getProperties(artifactId, MetadataScope.USER).isEmpty());

    // non-existing namespace
    addProperties(nonExistingApp, appProperties, NotFoundException.class);
    addProperties(nonExistingService, serviceProperties, NotFoundException.class);
    addProperties(nonExistingDataset, datasetProperties, NotFoundException.class);
    addProperties(nonExistingStream, streamProperties, NotFoundException.class);
    addProperties(nonExistingView, streamProperties, NotFoundException.class);
    addProperties(nonExistingArtifact, artifactProperties, NotFoundException.class);
  }

  @Test
  public void testTags() throws Exception {
    // should fail because we haven't provided any metadata in the request
    addTags(application, null, BadRequestException.class);
    Set<String> appTags = ImmutableSet.of("aTag", "aT", "Wow-WOW1", "WOW_WOW2");
    addTags(application, appTags);
    // should fail because we haven't provided any metadata in the request
    addTags(pingService, null, BadRequestException.class);
    Set<String> serviceTags = ImmutableSet.of("sTag", "sT");
    addTags(pingService, serviceTags);
    addTags(myds, null, BadRequestException.class);
    Set<String> datasetTags = ImmutableSet.of("dTag", "dT");
    addTags(myds, datasetTags);
    addTags(mystream, null, BadRequestException.class);
    Set<String> streamTags = ImmutableSet.of("stTag", "stT", "Wow-WOW1", "WOW_WOW2");
    addTags(mystream, streamTags);
    addTags(myview, null, BadRequestException.class);
    Set<String> viewTags = ImmutableSet.of("viewTag", "viewT");
    addTags(myview, viewTags);
    Set<String> artifactTags = ImmutableSet.of("rTag", "rT");
    addTags(artifactId, artifactTags);
    // retrieve tags and verify
    Set<String> tags = getTags(application, MetadataScope.USER);
    Assert.assertTrue(tags.containsAll(appTags));
    Assert.assertTrue(appTags.containsAll(tags));
    tags = getTags(pingService, MetadataScope.USER);
    Assert.assertTrue(tags.containsAll(serviceTags));
    Assert.assertTrue(serviceTags.containsAll(tags));
    tags = getTags(myds, MetadataScope.USER);
    Assert.assertTrue(tags.containsAll(datasetTags));
    Assert.assertTrue(datasetTags.containsAll(tags));
    tags = getTags(mystream, MetadataScope.USER);
    Assert.assertTrue(tags.containsAll(streamTags));
    Assert.assertTrue(streamTags.containsAll(tags));
    tags = getTags(myview, MetadataScope.USER);
    Assert.assertTrue(tags.containsAll(viewTags));
    Assert.assertTrue(viewTags.containsAll(tags));
    tags = getTags(artifactId, MetadataScope.USER);
    Assert.assertTrue(tags.containsAll(artifactTags));
    Assert.assertTrue(artifactTags.containsAll(tags));
    // test search for stream
    Set<MetadataSearchResultRecord> searchTags =
      searchMetadata(Id.Namespace.DEFAULT, "stT", MetadataSearchTargetType.STREAM);
    Set<MetadataSearchResultRecord> expected = ImmutableSet.of(
      new MetadataSearchResultRecord(mystream)
    );
    Assert.assertEquals(expected, searchTags);

    searchTags = searchMetadata(Id.Namespace.DEFAULT, "Wow", MetadataSearchTargetType.STREAM);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(mystream)
    );

    Assert.assertEquals(expected, searchTags);
    // test search for view with lowercase tag when metadata was stored in mixed case
    searchTags =
      searchMetadata(Id.Namespace.DEFAULT, "viewtag", MetadataSearchTargetType.VIEW);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(myview)
    );
    Assert.assertEquals(expected, searchTags);
    // test prefix search, should match stream and application
    searchTags = searchMetadata(Id.Namespace.DEFAULT, "Wow*", MetadataSearchTargetType.ALL);
    expected = ImmutableSet.of(
      new MetadataSearchResultRecord(application),
      new MetadataSearchResultRecord(mystream)
    );
    Assert.assertEquals(expected, searchTags);

    // search without any target param
    searchTags = searchMetadata(Id.Namespace.DEFAULT, "Wow*", null);
    Assert.assertEquals(expected, searchTags);

    // search non-existent tags should return empty set
    searchTags = searchMetadata(Id.Namespace.DEFAULT, "NullKey", null);
    Assert.assertEquals(ImmutableSet.<MetadataSearchResultRecord>of(), searchTags);

    // test removal
    removeTag(application, "aTag");
    Assert.assertEquals(ImmutableSet.of("aT", "Wow-WOW1", "WOW_WOW2"), getTags(application, MetadataScope.USER));
    removeTags(pingService);
    Assert.assertTrue(getTags(pingService, MetadataScope.USER).isEmpty());
    removeTags(pingService);
    Assert.assertTrue(getTags(pingService, MetadataScope.USER).isEmpty());
    removeTag(myds, "dT");
    Assert.assertEquals(ImmutableSet.of("dTag"), getTags(myds, MetadataScope.USER));
    removeTag(mystream, "stT");
    removeTag(mystream, "stTag");
    removeTag(mystream, "Wow-WOW1");
    removeTag(mystream, "WOW_WOW2");
    removeTag(myview, "viewT");
    removeTag(myview, "viewTag");
    Assert.assertTrue(getTags(mystream, MetadataScope.USER).isEmpty());
    removeTag(artifactId, "rTag");
    removeTag(artifactId, "rT");
    Assert.assertTrue(getTags(artifactId, MetadataScope.USER).isEmpty());
    // cleanup
    removeTags(application);
    removeTags(pingService);
    removeTags(myds);
    removeTags(mystream);
    removeTags(myview);
    removeTags(artifactId);
    Assert.assertTrue(getTags(application, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getTags(pingService, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getTags(myds, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getTags(mystream, MetadataScope.USER).isEmpty());
    Assert.assertTrue(getTags(artifactId, MetadataScope.USER).isEmpty());
    // non-existing namespace
    addTags(nonExistingApp, appTags, NotFoundException.class);
    addTags(nonExistingService, serviceTags, NotFoundException.class);
    addTags(nonExistingDataset, datasetTags, NotFoundException.class);
    addTags(nonExistingStream, streamTags, NotFoundException.class);
    addTags(nonExistingView, streamTags, NotFoundException.class);
    addTags(nonExistingArtifact, artifactTags, NotFoundException.class);
  }

  @Test
  public void testMetadata() throws Exception {
    assertCleanState(MetadataScope.USER);
    // Remove when nothing exists
    removeAllMetadata();
    assertCleanState(MetadataScope.USER);
    // Add some properties and tags
    Map<String, String> appProperties = ImmutableMap.of("aKey", "aValue");
    Map<String, String> serviceProperties = ImmutableMap.of("sKey", "sValue");
    Map<String, String> datasetProperties = ImmutableMap.of("dKey", "dValue");
    Map<String, String> streamProperties = ImmutableMap.of("stKey", "stValue");
    Map<String, String> viewProperties = ImmutableMap.of("viewKey", "viewValue");
    Map<String, String> artifactProperties = ImmutableMap.of("rKey", "rValue");
    Set<String> appTags = ImmutableSet.of("aTag");
    Set<String> serviceTags = ImmutableSet.of("sTag");
    Set<String> datasetTags = ImmutableSet.of("dTag");
    Set<String> streamTags = ImmutableSet.of("stTag");
    Set<String> viewTags = ImmutableSet.of("viewTag");
    Set<String> artifactTags = ImmutableSet.of("rTag");
    addProperties(application, appProperties);
    addProperties(pingService, serviceProperties);
    addProperties(myds, datasetProperties);
    addProperties(mystream, streamProperties);
    addProperties(myview, viewProperties);
    addProperties(artifactId, artifactProperties);
    addTags(application, appTags);
    addTags(pingService, serviceTags);
    addTags(myds, datasetTags);
    addTags(mystream, streamTags);
    addTags(myview, viewTags);
    addTags(artifactId, artifactTags);
    // verify app
    Set<MetadataRecord> metadataRecords = getMetadata(application, MetadataScope.USER);
    Assert.assertEquals(1, metadataRecords.size());
    MetadataRecord metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(application, metadata.getEntityId());
    Assert.assertEquals(appProperties, metadata.getProperties());
    Assert.assertEquals(appTags, metadata.getTags());
    // verify service
    metadataRecords = getMetadata(pingService, MetadataScope.USER);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(pingService, metadata.getEntityId());
    Assert.assertEquals(serviceProperties, metadata.getProperties());
    Assert.assertEquals(serviceTags, metadata.getTags());
    // verify dataset
    metadataRecords = getMetadata(myds, MetadataScope.USER);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(myds, metadata.getEntityId());
    Assert.assertEquals(datasetProperties, metadata.getProperties());
    Assert.assertEquals(datasetTags, metadata.getTags());
    // verify stream
    metadataRecords = getMetadata(mystream, MetadataScope.USER);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(mystream, metadata.getEntityId());
    Assert.assertEquals(streamProperties, metadata.getProperties());
    Assert.assertEquals(streamTags, metadata.getTags());
    // verify view
    metadataRecords = getMetadata(myview, MetadataScope.USER);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(myview, metadata.getEntityId());
    Assert.assertEquals(viewProperties, metadata.getProperties());
    Assert.assertEquals(viewTags, metadata.getTags());
    // verify artifact
    metadataRecords = getMetadata(artifactId, MetadataScope.USER);
    Assert.assertEquals(1, metadataRecords.size());
    metadata = metadataRecords.iterator().next();
    Assert.assertEquals(MetadataScope.USER, metadata.getScope());
    Assert.assertEquals(artifactId, metadata.getEntityId());
    Assert.assertEquals(artifactProperties, metadata.getProperties());
    Assert.assertEquals(artifactTags, metadata.getTags());
    // cleanup
    removeAllMetadata();
    assertCleanState(MetadataScope.USER);
  }

  @Test
  public void testDeleteApplication() throws Exception {
    deploy(WordCountApp.class, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
    Id.Program program = Id.Program.from(TEST_NAMESPACE1, "WordCountApp", ProgramType.FLOW, "WordCountFlow");

    // Set some properties metadata
    Map<String, String> flowProperties = ImmutableMap.of("sKey", "sValue", "sK", "sV");
    addProperties(program, flowProperties);

    // Get properties
    Map<String, String> properties = getProperties(program, MetadataScope.USER);
    Assert.assertEquals(2, properties.size());

    //Delete the App after stopping the flow
    org.apache.http.HttpResponse response =
      doDelete(getVersionedAPIPath("apps/WordCountApp/", Constants.Gateway.API_VERSION_3_TOKEN,
                                   TEST_NAMESPACE1));
    Assert.assertEquals(200, response.getStatusLine().getStatusCode());
    response = doDelete(getVersionedAPIPath("apps/WordCountApp/", Constants.Gateway.API_VERSION_3_TOKEN,
                                            TEST_NAMESPACE1));
    Assert.assertEquals(404, response.getStatusLine().getStatusCode());

    // Now try to get from invalid entity should throw 404.
    getPropertiesFromInvalidEntity(program);
  }

  @Test
  public void testInvalidEntities() throws IOException {
    Id.Program nonExistingProgram = Id.Program.from(application, ProgramType.SERVICE, "NonExistingService");
    Id.DatasetInstance nonExistingDataset = Id.DatasetInstance.from(Id.Namespace.DEFAULT, "NonExistingDataset");
    Id.Stream nonExistingStream = Id.Stream.from(Id.Namespace.DEFAULT, "NonExistingStream");
    Id.Stream.View nonExistingView = Id.Stream.View.from(mystream, "NonExistingView");
    Id.Application nonExistingApp = Id.Application.from(Id.Namespace.DEFAULT, "NonExistingApp");

    Map<String, String> properties = ImmutableMap.of("aKey", "aValue", "aK", "aV");
    addProperties(nonExistingApp, properties, NotFoundException.class);
    addProperties(nonExistingProgram, properties, NotFoundException.class);
    addProperties(nonExistingDataset, properties, NotFoundException.class);
    addProperties(nonExistingView, properties, NotFoundException.class);
    addProperties(nonExistingStream, properties, NotFoundException.class);
  }

  @Test
  public void testInvalidProperties() throws IOException {
    // Test length
    StringBuilder builder = new StringBuilder(100);
    for (int i = 0; i < 100; i++) {
      builder.append("a");
    }
    Map<String, String> properties = ImmutableMap.of("aKey", builder.toString());
    addProperties(application, properties, BadRequestException.class);
    properties = ImmutableMap.of(builder.toString(), "aValue");
    addProperties(application, properties, BadRequestException.class);

    // Try to add tag as property
    properties = ImmutableMap.of("tags", "aValue");
    addProperties(application, properties, BadRequestException.class);

    // Invalid chars
    properties = ImmutableMap.of("aKey$", "aValue");
    addProperties(application, properties, BadRequestException.class);

    properties = ImmutableMap.of("aKey", "aValue$");
    addProperties(application, properties, BadRequestException.class);
  }

  @Test
  public void testInvalidTags() throws IOException {
    // Invalid chars
    Set<String> tags = ImmutableSet.of("aTag$");
    addTags(application, tags, BadRequestException.class);

    // Test length
    StringBuilder builder = new StringBuilder(100);
    for (int i = 0; i < 100; i++) {
      builder.append("a");
    }
    tags = ImmutableSet.of(builder.toString());
    addTags(application, tags, BadRequestException.class);
  }

  @Test
  public void testDeletedProgramHandlerStage() throws Exception {
    deploy(WordCountApp.class, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);
    Id.Program program = Id.Program.from(TEST_NAMESPACE1, "WordCountApp", ProgramType.FLOW, "WordCountFlow");

    // Set some properties metadata
    Map<String, String> flowProperties = ImmutableMap.of("sKey", "sValue", "sK", "sV");
    addProperties(program, flowProperties);

    // Get properties
    Map<String, String> properties = getProperties(program, MetadataScope.USER);
    Assert.assertEquals(2, properties.size());

    // Deploy WordCount App without Flow program. No need to start/stop the flow.
    deploy(WordCountMinusFlowApp.class, Constants.Gateway.API_VERSION_3_TOKEN, TEST_NAMESPACE1);

    // Get properties from deleted (flow) program - should return 404
    getPropertiesFromInvalidEntity(program);

    // Delete the App after stopping the flow
    final Id.Application wordCountApp = Id.Application.from(TEST_NAMESPACE1, "WordCountApp");
    deleteApp(wordCountApp, 200);
  }

  @Test
  public void testSystemMetadataRetrieval() throws Exception {
    deploy(AllProgramsApp.class);
    // verify stream system metadata
    Id.Stream streamId = Id.Stream.from(Id.Namespace.DEFAULT, AllProgramsApp.STREAM_NAME);
    Set<String> streamSystemTags = getTags(streamId, MetadataScope.SYSTEM);
    Assert.assertEquals(ImmutableSet.of(AllProgramsApp.STREAM_NAME), streamSystemTags);
    Map<String, String> streamSystemProperties = getProperties(streamId, MetadataScope.SYSTEM);
    Assert.assertEquals(ImmutableMap.of("schema",
                                        Schema.recordOf("stringBody",
                                                        Schema.Field.of("body",
                                                                        Schema.of(Schema.Type.STRING))).toString(),
                                        "ttl", String.valueOf(Long.MAX_VALUE)), streamSystemProperties);
    Set<MetadataRecord> streamSystemMetadata = getMetadata(streamId, MetadataScope.SYSTEM);
    Assert.assertEquals(
      ImmutableSet.of(new MetadataRecord(streamId, MetadataScope.SYSTEM, streamSystemProperties, streamSystemTags)),
      streamSystemMetadata);
    // create view and verify view system metadata
    Id.Stream.View view = Id.Stream.View.from(streamId, "view");
    Schema viewSchema = Schema.recordOf("record",
                                        Schema.Field.of("viewBody", Schema.nullableOf(Schema.of(Schema.Type.BYTES))));
    createOrUpdateView(view, new ViewSpecification(new FormatSpecification("format", viewSchema)));
    Set<String> viewSystemTags = getTags(view, MetadataScope.SYSTEM);
    Assert.assertEquals(ImmutableSet.of("view", AllProgramsApp.STREAM_NAME), viewSystemTags);
    Map<String, String> viewSystemProperties = getProperties(view, MetadataScope.SYSTEM);
    Assert.assertEquals(viewSchema.toString(), viewSystemProperties.get("schema"));
    ImmutableSet<String> viewUserTags = ImmutableSet.of("viewTag");
    addTags(view, viewUserTags);
    Assert.assertEquals(
      ImmutableSet.of(new MetadataRecord(view, MetadataScope.USER, ImmutableMap.<String, String>of(), viewUserTags),
                      new MetadataRecord(view, MetadataScope.SYSTEM, viewSystemProperties, viewSystemTags)),
      getMetadata(view)
    );
    // verify dataset system metadata
    Id.DatasetInstance datasetInstance = Id.DatasetInstance.from(Id.Namespace.DEFAULT, AllProgramsApp.DATASET_NAME);
    Set<String> dsSystemTags = getTags(datasetInstance, MetadataScope.SYSTEM);
    Assert.assertEquals(
      ImmutableSet.of(AllProgramsApp.DATASET_NAME,
                      DatasetSystemMetadataWriter.BATCH_TAG,
                      DatasetSystemMetadataWriter.EXPLORE_TAG),
      dsSystemTags);
    Map<String, String> dsSystemProperties = getProperties(datasetInstance, MetadataScope.SYSTEM);
    Assert.assertEquals(KeyValueTable.class.getName(), dsSystemProperties.get("type"));
    // verify artifact metadata
    Id.Artifact artifactId = getArtifactId();
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataRecord(artifactId, MetadataScope.SYSTEM, ImmutableMap.<String, String>of(),
                           ImmutableSet.of(AllProgramsApp.class.getSimpleName()))
      ),
      getMetadata(artifactId, MetadataScope.SYSTEM)
    );
    // verify app system metadata
    Id.Application app = Id.Application.from(Id.Namespace.DEFAULT, AllProgramsApp.NAME);
    Assert.assertEquals(
      ImmutableMap.builder()
        .put(ProgramType.FLOW.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + AllProgramsApp.NoOpFlow.NAME,
             AllProgramsApp.NoOpFlow.NAME)
        .put(ProgramType.MAPREDUCE.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + AllProgramsApp.NoOpMR.NAME,
             AllProgramsApp.NoOpMR.NAME)
        .put(ProgramType.SERVICE.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR +
               AllProgramsApp.NoOpService.NAME, AllProgramsApp.NoOpService.NAME)
        .put(ProgramType.SPARK.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + AllProgramsApp.NoOpSpark.NAME,
             AllProgramsApp.NoOpSpark.NAME)
        .put(ProgramType.WORKER.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + AllProgramsApp.NoOpWorker.NAME,
             AllProgramsApp.NoOpWorker.NAME)
        .put(ProgramType.WORKFLOW.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR
               + AllProgramsApp.NoOpWorkflow.NAME, AllProgramsApp.NoOpWorkflow.NAME)
        .put("schedule" + MetadataDataset.KEYVALUE_SEPARATOR + AllProgramsApp.SCHEDULE_NAME,
             AllProgramsApp.SCHEDULE_NAME + MetadataDataset.KEYVALUE_SEPARATOR + AllProgramsApp.SCHEDULE_DESCRIPTION)
        .build(),
      getProperties(app, MetadataScope.SYSTEM));
    Assert.assertEquals(ImmutableSet.of(AllProgramsApp.class.getSimpleName(), AllProgramsApp.NAME),
                        getTags(app, MetadataScope.SYSTEM));
    // verify program system metadata
    assertProgramSystemMetadata(Id.Program.from(app, ProgramType.FLOW, AllProgramsApp.NoOpFlow.NAME), "Realtime");
    assertProgramSystemMetadata(Id.Program.from(app, ProgramType.WORKER, AllProgramsApp.NoOpWorker.NAME), "Realtime");
    assertProgramSystemMetadata(Id.Program.from(app, ProgramType.SERVICE, AllProgramsApp.NoOpService.NAME), "Realtime");
    assertProgramSystemMetadata(Id.Program.from(app, ProgramType.MAPREDUCE, AllProgramsApp.NoOpMR.NAME), "Batch");
    assertProgramSystemMetadata(Id.Program.from(app, ProgramType.SPARK, AllProgramsApp.NoOpSpark.NAME), "Batch");
    assertProgramSystemMetadata(Id.Program.from(app, ProgramType.WORKFLOW, AllProgramsApp.NoOpWorkflow.NAME), "Batch");
  }

  @Test
  public void testSearchUsingSystemMetadata() throws Exception {
    deploy(AllProgramsApp.class);
    Id.Application app = Id.Application.from(Id.Namespace.DEFAULT, AllProgramsApp.NAME);
    Id.Artifact artifact = getArtifactId();
    try {
      // search artifacts
      assertArtifactSearch();
      // search app
      assertAppSearch(app, artifact);
      // search programs
      assertProgramSearch(app);
      // search data entities
      assertDataEntitySearch();
    } finally {
      // cleanup
      deleteApp(app, HttpResponseStatus.OK.getCode());
      deleteArtifact(artifact, HttpResponseStatus.OK.getCode());
    }
  }

  @Test
  public void testSystemScopeArtifacts() throws Exception {
    // add a system artifact. currently can't do this through the rest api (by design)
    // so bypass it and use the repository directly
    Id.Artifact systemId = Id.Artifact.from(Id.Namespace.SYSTEM, "wordcount", "1.0.0");
    File systemArtifact = buildAppArtifact(WordCountApp.class, "wordcount-1.0.0.jar");
    ArtifactRepository artifactRepository = getInjector().getInstance(ArtifactRepository.class);
    artifactRepository.addArtifact(systemId, systemArtifact, new HashSet<ArtifactRange>());

    // verify that user metadata can be added for system-scope artifacts
    Map<String, String> userProperties = ImmutableMap.of("systemArtifactKey", "systemArtifactValue");
    ImmutableSet<String> userTags = ImmutableSet.of("systemArtifactTag");
    addProperties(systemId, userProperties);
    addTags(systemId, userTags);

    // verify that user and system metadata can be retrieved for system-scope artifacts
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataRecord(systemId, MetadataScope.USER, userProperties, userTags),
        new MetadataRecord(systemId, MetadataScope.SYSTEM,
                           ImmutableMap.<String, String>of(), ImmutableSet.of("wordcount"))
      ),
      getMetadata(systemId)
    );

    // verify that system scope artifacts can be returned by a search in the default namespace
    // with no target type
    Assert.assertEquals(
      ImmutableSet.of(new MetadataSearchResultRecord(systemId)),
      searchMetadata(Id.Namespace.DEFAULT, "system*", null)
    );
    // with target type as artifact
    Assert.assertEquals(
      ImmutableSet.of(new MetadataSearchResultRecord(systemId)),
      searchMetadata(Id.Namespace.DEFAULT, "system*", MetadataSearchTargetType.ARTIFACT)
    );

    // verify that user metadata can be deleted for system-scope artifacts
    removeMetadata(systemId);
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataRecord(systemId, MetadataScope.USER, ImmutableMap.<String, String>of(), ImmutableSet.<String>of()),
        new MetadataRecord(systemId, MetadataScope.SYSTEM,
                           ImmutableMap.<String, String>of(), ImmutableSet.of("wordcount"))
      ),
      getMetadata(systemId)
    );
    deleteArtifact(systemId, HttpResponseStatus.OK.getCode());
  }

  @Test
  public void testScopeQueryParam() throws Exception {
    deploy(WordCountApp.class);
    Id.Application app = Id.Application.from(Id.Namespace.DEFAULT, WordCountApp.class.getSimpleName());
    RESTClient restClient = new RESTClient(clientConfig);
    URL url = clientConfig.resolveNamespacedURLV3(Id.Namespace.DEFAULT, "apps/WordCountApp/metadata?scope=system");
    Assert.assertEquals(
      HttpResponseStatus.OK.getCode(),
      restClient.execute(HttpRequest.get(url).build(), null).getResponseCode()
    );
    url = clientConfig.resolveNamespacedURLV3(Id.Namespace.DEFAULT,
                                              "datasets/mydataset/metadata/properties?scope=SySTeM");
    Assert.assertEquals(
      HttpResponseStatus.OK.getCode(),
      restClient.execute(HttpRequest.get(url).build(), null).getResponseCode()
    );
    url = clientConfig.resolveNamespacedURLV3(Id.Namespace.DEFAULT,
                                              "apps/WordCountApp/flows/WordCountFlow/metadata/tags?scope=USER");
    Assert.assertEquals(
      HttpResponseStatus.OK.getCode(),
      restClient.execute(HttpRequest.get(url).build(), null).getResponseCode()
    );
    url = clientConfig.resolveNamespacedURLV3(Id.Namespace.DEFAULT, "streams/text/metadata?scope=user");
    Assert.assertEquals(
      HttpResponseStatus.OK.getCode(),
      restClient.execute(HttpRequest.get(url).build(), null).getResponseCode()
    );
    url = clientConfig.resolveNamespacedURLV3(Id.Namespace.DEFAULT, "streams/text/metadata?scope=blah");
    Assert.assertEquals(
      HttpResponseStatus.BAD_REQUEST.getCode(),
      restClient.execute(HttpRequest.get(url).build(), null, HttpResponseStatus.BAD_REQUEST.getCode()).getResponseCode()
    );
    deleteApp(app, HttpResponseStatus.OK.getCode());
    // deleting the app does not delete the dataset and stream, delete them explicitly to clear their system metadata
    getInjector().getInstance(DatasetFramework.class)
      .deleteInstance(Id.DatasetInstance.from(Id.Namespace.DEFAULT, "mydataset"));
    getInjector().getInstance(StreamAdmin.class).drop(Id.Stream.from(Id.Namespace.DEFAULT, "text"));
  }

  private void assertProgramSystemMetadata(Id.Program programId, String mode) throws Exception {
    Assert.assertTrue(getProperties(programId, MetadataScope.SYSTEM).isEmpty());
    Set<String> expected = ImmutableSet.of(programId.getId(), programId.getType().getPrettyName(), mode);
    if (ProgramType.WORKFLOW == programId.getType()) {
      expected = ImmutableSet.of(programId.getId(), programId.getType().getPrettyName(), mode,
                                 AllProgramsApp.NoOpAction.class.getSimpleName(), AllProgramsApp.NoOpMR.NAME);
    }
    Assert.assertEquals(expected, getTags(programId, MetadataScope.SYSTEM));
  }

  private void assertArtifactSearch() throws Exception {
    // add a plugin artifact.
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(ManifestFields.EXPORT_PACKAGE,
                                     AllProgramsApp.AppPlugin.class.getPackage().getName());
    Id.Artifact pluginArtifact = Id.Artifact.from(Id.Namespace.DEFAULT, "plugins", "1.0.0");
    Assert.assertEquals(HttpResponseStatus.OK.getCode(),
                        addPluginArtifact(pluginArtifact, AllProgramsApp.AppPlugin.class, manifest,
                                          new HashSet<ArtifactRange>()).getStatusLine().getStatusCode());
    // search using artifact name
    Set<MetadataSearchResultRecord> expected = ImmutableSet.of(new MetadataSearchResultRecord(pluginArtifact));
    Set<MetadataSearchResultRecord> results = searchMetadata(Id.Namespace.DEFAULT, "plugins", null);
    Assert.assertEquals(expected, results);
    // search the artifact given a plugin
    results = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.PLUGIN_TYPE, null);
    Assert.assertEquals(expected, results);
    results = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.PLUGIN_NAME + ":" + AllProgramsApp.PLUGIN_TYPE, null);
    Assert.assertEquals(expected, results);
    results = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.PLUGIN_NAME, MetadataSearchTargetType.ARTIFACT);
    Assert.assertEquals(expected, results);
    // add a user tag to the application with the same name as the plugin
    addTags(application, ImmutableSet.of(AllProgramsApp.PLUGIN_NAME));
    // search for all entities with plugin name. Should return both artifact and application
    results = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.PLUGIN_NAME, null);
    Assert.assertEquals(
      ImmutableSet.of(new MetadataSearchResultRecord(application), new MetadataSearchResultRecord(pluginArtifact)),
      results);
    // search for all entities for a plugin with the plugin name. Should return only the artifact, since for the
    // application, its just a tag, not a plugin
    results = searchMetadata(Id.Namespace.DEFAULT, "plugin:" + AllProgramsApp.PLUGIN_NAME + ":*", null);
    Assert.assertEquals(expected, results);
  }

  private void assertAppSearch(Id.Application app, Id.Artifact artifact) throws Exception {
    // using app name
    ImmutableSet<MetadataSearchResultRecord> expected = ImmutableSet.of(new MetadataSearchResultRecord(app));
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NAME, null));
    // using artifact name: both app and artifact should match
    Assert.assertEquals(
      ImmutableSet.of(new MetadataSearchResultRecord(app), new MetadataSearchResultRecord(artifact)),
      searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.class.getSimpleName(), null));
    // using program names
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpFlow.NAME,
                                                 MetadataSearchTargetType.APP));
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpMR.NAME,
                                                 MetadataSearchTargetType.APP));
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpService.NAME,
                                                 MetadataSearchTargetType.APP));
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpSpark.NAME,
                                                 MetadataSearchTargetType.APP));
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpWorker.NAME,
                                                 MetadataSearchTargetType.APP));
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpWorkflow.NAME,
                                                 MetadataSearchTargetType.APP));
    // using program types
    Assert.assertEquals(
      expected, searchMetadata(Id.Namespace.DEFAULT,
                               ProgramType.FLOW.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + "*",
                               MetadataSearchTargetType.APP));
    Assert.assertEquals(
      expected, searchMetadata(Id.Namespace.DEFAULT,
                               ProgramType.MAPREDUCE.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + "*",
                               MetadataSearchTargetType.APP));
    Assert.assertEquals(
      ImmutableSet.builder().addAll(expected).add(new MetadataSearchResultRecord(application)).build(),
      searchMetadata(Id.Namespace.DEFAULT,
                     ProgramType.SERVICE.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + "*",
                     MetadataSearchTargetType.APP));
    Assert.assertEquals(
      expected, searchMetadata(Id.Namespace.DEFAULT,
                               ProgramType.SPARK.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + "*",
                               MetadataSearchTargetType.APP));
    Assert.assertEquals(
      expected, searchMetadata(Id.Namespace.DEFAULT,
                               ProgramType.WORKER.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + "*",
                               MetadataSearchTargetType.APP));
    Assert.assertEquals(
      expected, searchMetadata(Id.Namespace.DEFAULT,
                               ProgramType.WORKFLOW.getPrettyName() + MetadataDataset.KEYVALUE_SEPARATOR + "*",
                               MetadataSearchTargetType.APP));

    // using schedule
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.SCHEDULE_NAME, null));
    Assert.assertEquals(expected, searchMetadata(Id.Namespace.DEFAULT, "EveryMinute", null));
  }

  private void assertProgramSearch(Id.Application app) throws Exception {
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.MAPREDUCE, AllProgramsApp.NoOpMR.NAME)),
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.WORKFLOW, AllProgramsApp.NoOpWorkflow.NAME)),
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.SPARK, AllProgramsApp.NoOpSpark.NAME)),
        new MetadataSearchResultRecord(Id.DatasetInstance.from(Id.Namespace.DEFAULT, AllProgramsApp.DATASET_NAME)),
        new MetadataSearchResultRecord(
          Id.DatasetInstance.from(Id.Namespace.DEFAULT, AllProgramsApp.DS_WITH_SCHEMA_NAME)),
        new MetadataSearchResultRecord(myds)
      ),
      searchMetadata(Id.Namespace.DEFAULT, "Batch", null));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.FLOW, AllProgramsApp.NoOpFlow.NAME)),
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.SERVICE, AllProgramsApp.NoOpService.NAME)),
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.WORKER, AllProgramsApp.NoOpWorker.NAME)),
        new MetadataSearchResultRecord(
          Id.Program.from(Id.Application.from(Id.Namespace.DEFAULT, AppWithDataset.class.getSimpleName()),
                          ProgramType.SERVICE, "PingService"))
      ),
      searchMetadata(Id.Namespace.DEFAULT, "Realtime", null));

    // Using program names
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.FLOW, AllProgramsApp.NoOpFlow.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpFlow.NAME, MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.MAPREDUCE, AllProgramsApp.NoOpMR.NAME)),
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.WORKFLOW, AllProgramsApp.NoOpWorkflow.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpMR.NAME, MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.SERVICE, AllProgramsApp.NoOpService.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpService.NAME, MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.SPARK, AllProgramsApp.NoOpSpark.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpSpark.NAME, MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.WORKER, AllProgramsApp.NoOpWorker.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpWorker.NAME, MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.WORKFLOW, AllProgramsApp.NoOpWorkflow.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.NoOpWorkflow.NAME, MetadataSearchTargetType.PROGRAM));

    // using program types
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.FLOW, AllProgramsApp.NoOpFlow.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, ProgramType.FLOW.getPrettyName(), MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.MAPREDUCE, AllProgramsApp.NoOpMR.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, ProgramType.MAPREDUCE.getPrettyName(), MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.SERVICE, AllProgramsApp.NoOpService.NAME)),
        new MetadataSearchResultRecord(pingService)),
      searchMetadata(Id.Namespace.DEFAULT, ProgramType.SERVICE.getPrettyName(), MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.SPARK, AllProgramsApp.NoOpSpark.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, ProgramType.SPARK.getPrettyName(), MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.WORKER, AllProgramsApp.NoOpWorker.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, ProgramType.WORKER.getPrettyName(), MetadataSearchTargetType.PROGRAM));
    Assert.assertEquals(
      ImmutableSet.of(
        new MetadataSearchResultRecord(Id.Program.from(app, ProgramType.WORKFLOW, AllProgramsApp.NoOpWorkflow.NAME))),
      searchMetadata(Id.Namespace.DEFAULT, ProgramType.WORKFLOW.getPrettyName(), MetadataSearchTargetType.PROGRAM));
  }

  private void assertDataEntitySearch() throws Exception {
    Id.DatasetInstance datasetInstance = Id.DatasetInstance.from(Id.Namespace.DEFAULT, AllProgramsApp.DATASET_NAME);
    Id.DatasetInstance dsWithSchema = Id.DatasetInstance.from(Id.Namespace.DEFAULT, AllProgramsApp.DS_WITH_SCHEMA_NAME);
    Id.Stream streamId = Id.Stream.from(Id.Namespace.DEFAULT, AllProgramsApp.STREAM_NAME);
    Id.Stream.View view = Id.Stream.View.from(streamId, "view");

    Set<MetadataSearchResultRecord> expected = ImmutableSet.of(
      new MetadataSearchResultRecord(streamId),
      new MetadataSearchResultRecord(mystream)
    );

    // schema search with fieldname
    Set<MetadataSearchResultRecord> metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "body", null);
    Assert.assertEquals(expected, metadataSearchResultRecords);

    // schema search with fieldname and fieldtype
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "body:" + Schema.Type.STRING.toString(), null);
    Assert.assertEquals(expected, metadataSearchResultRecords);

    // schema search for partial fieldname
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "bo*", null);
    Assert.assertEquals(expected, metadataSearchResultRecords);

    // schema search with fieldname and all/partial fieldtype
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "body:STR*", null);
    Assert.assertEquals(expected, metadataSearchResultRecords);

    // schema search for a field with the given fieldname:fieldtype
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "body:STRING+field1:STRING", null);
    Assert.assertEquals(ImmutableSet.<MetadataSearchResultRecord>builder()
                          .addAll(expected)
                          .add(new MetadataSearchResultRecord(dsWithSchema))
                          .build(),
                        metadataSearchResultRecords);

    // create a view
    Schema viewSchema = Schema.recordOf("record",
                                        Schema.Field.of("viewBody", Schema.nullableOf(Schema.of(Schema.Type.BYTES))));
    createOrUpdateView(view, new ViewSpecification(new FormatSpecification("format", viewSchema)));

    // search all entities that have a defined schema
    // add a user property with "schema" as key
    Map<String, String> datasetProperties = ImmutableMap.of("schema", "schemaValue");
    addProperties(datasetInstance, datasetProperties);

    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "schema:*", null);
    Assert.assertEquals(ImmutableSet.<MetadataSearchResultRecord>builder()
                          .addAll(expected)
                          .add(new MetadataSearchResultRecord(datasetInstance))
                          .add(new MetadataSearchResultRecord(dsWithSchema))
                          .add(new MetadataSearchResultRecord(view))
                          .build(),
                        metadataSearchResultRecords);

    // search dataset
    ImmutableSet<MetadataSearchResultRecord> expectedKvTables = ImmutableSet.of(
      new MetadataSearchResultRecord(datasetInstance), new MetadataSearchResultRecord(myds)
    );
    ImmutableSet<MetadataSearchResultRecord> expectedAllDatasets = ImmutableSet.<MetadataSearchResultRecord>builder()
      .addAll(expectedKvTables)
      .add(new MetadataSearchResultRecord(dsWithSchema))
      .build();
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "explore", null);
    Assert.assertEquals(expectedAllDatasets, metadataSearchResultRecords);
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, KeyValueTable.class.getName(), null);
    Assert.assertEquals(expectedKvTables, metadataSearchResultRecords);
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "type:*", null);
    Assert.assertEquals(expectedAllDatasets, metadataSearchResultRecords);

    // search using ttl
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "ttl:*", null);
    Assert.assertEquals(expected, metadataSearchResultRecords);

    // search using names
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.STREAM_NAME, null);
    Assert.assertEquals(
      ImmutableSet.of(new MetadataSearchResultRecord(streamId), new MetadataSearchResultRecord(view)),
      metadataSearchResultRecords);

    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.STREAM_NAME,
                                                 MetadataSearchTargetType.STREAM);
    Assert.assertEquals(ImmutableSet.of(new MetadataSearchResultRecord(streamId)), metadataSearchResultRecords);
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.STREAM_NAME,
                                                 MetadataSearchTargetType.VIEW);
    Assert.assertEquals(ImmutableSet.of(new MetadataSearchResultRecord(view)), metadataSearchResultRecords);
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, "view",
                                                 MetadataSearchTargetType.VIEW);
    Assert.assertEquals(ImmutableSet.of(new MetadataSearchResultRecord(view)), metadataSearchResultRecords);
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.DATASET_NAME, null);
    Assert.assertEquals(ImmutableSet.of(new MetadataSearchResultRecord(datasetInstance)), metadataSearchResultRecords);
    metadataSearchResultRecords = searchMetadata(Id.Namespace.DEFAULT, AllProgramsApp.DS_WITH_SCHEMA_NAME, null);
    Assert.assertEquals(ImmutableSet.of(new MetadataSearchResultRecord(dsWithSchema)), metadataSearchResultRecords);
  }

  private void removeAllMetadata() throws Exception {
    removeMetadata(application);
    removeMetadata(pingService);
    removeMetadata(myds);
    removeMetadata(mystream);
    removeMetadata(myview);
    removeMetadata(artifactId);
  }

  private void assertCleanState(@Nullable MetadataScope scope) throws Exception {
    assertEmptyMetadata(getMetadata(application, scope), scope);
    assertEmptyMetadata(getMetadata(pingService, scope), scope);
    assertEmptyMetadata(getMetadata(myds, scope), scope);
    assertEmptyMetadata(getMetadata(mystream, scope), scope);
    assertEmptyMetadata(getMetadata(myview, scope), scope);
    assertEmptyMetadata(getMetadata(artifactId, scope), scope);
  }

  private void assertEmptyMetadata(Set<MetadataRecord> entityMetadata, @Nullable MetadataScope scope) {
    // should have two metadata records - one for each scope, both should have empty properties and tags
    int expectedRecords = (scope == null) ? 2 : 1;
    Assert.assertEquals(expectedRecords, entityMetadata.size());
    for (MetadataRecord metadataRecord : entityMetadata) {
      Assert.assertTrue(metadataRecord.getProperties().isEmpty());
      Assert.assertTrue(metadataRecord.getTags().isEmpty());
    }
  }

  /**
   * Returns the artifact id of the deployed application. Need this because we don't know the exact version.
   */
  private Id.Artifact getArtifactId() throws Exception {
    Iterable<JsonObject> filtered =
      Iterables.filter(getArtifacts(Id.Namespace.DEFAULT.getId()), new Predicate<JsonObject>() {
        @Override
        public boolean apply(JsonObject input) {
          return AllProgramsApp.class.getSimpleName().equals(input.get("name").getAsString());
        }
      });
    JsonObject allProgramsArtifact = Iterables.getOnlyElement(filtered);
    return Id.Artifact.from(Id.Namespace.DEFAULT, allProgramsArtifact.get("name").getAsString(),
                            allProgramsArtifact.get("version").getAsString());
  }
}
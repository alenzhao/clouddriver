/*
 * Copyright 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.deploy

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.batch.BatchRequest
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.compute.Compute
import com.google.api.services.compute.model.*
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.google.deploy.description.BaseGoogleInstanceDescription
import com.netflix.spinnaker.clouddriver.google.deploy.description.BasicGoogleDeployDescription
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleLoadBalancerDescription
import com.netflix.spinnaker.clouddriver.google.deploy.exception.GoogleResourceNotFoundException
import com.netflix.spinnaker.clouddriver.google.deploy.ops.loadbalancer.UpsertGoogleHttpLoadBalancerAtomicOperation
import com.netflix.spinnaker.clouddriver.google.model.GoogleAutoscalingPolicy
import com.netflix.spinnaker.clouddriver.google.model.GoogleServerGroup
import com.netflix.spinnaker.clouddriver.google.model.loadbalancing.*
import com.netflix.spinnaker.clouddriver.google.provider.view.GoogleLoadBalancerProvider
import com.netflix.spinnaker.clouddriver.google.security.FakeGoogleCredentials
import com.netflix.spinnaker.clouddriver.google.security.GoogleNamedAccountCredentials
import groovy.mock.interceptor.MockFor
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static com.netflix.spinnaker.clouddriver.google.deploy.ops.loadbalancer.UpsertGoogleHttpLoadBalancerTestConstants.*

class GCEUtilSpec extends Specification {
  private static final PROJECT_NAME = "my-project"
  private static final REGION = "us-central1"
  private static final ZONE = "us-central1-f"
  private static final IMAGE_NAME = "some-image-name"
  private static final PHASE = "SOME-PHASE"
  private static final INSTANCE_LOCAL_NAME_1 = "some-instance-name-1"
  private static final INSTANCE_LOCAL_NAME_2 = "some-instance-name-2"
  private static final INSTANCE_URL_1 = "https://www.googleapis.com/compute/v1/projects/$PROJECT_NAME/zones/us-central1-b/instances/$INSTANCE_LOCAL_NAME_1"
  private static final INSTANCE_URL_2 = "https://www.googleapis.com/compute/v1/projects/$PROJECT_NAME/zones/us-central1-b/instances/$INSTANCE_LOCAL_NAME_2"
  private static final BASE_DESCRIPTION_1 = new BaseGoogleInstanceDescription(image: IMAGE_NAME)
  private static final IMAGE_PROJECT_NAME = "some-image-project"
  private static final CREDENTIALS = new GoogleNamedAccountCredentials.Builder().imageProjects([IMAGE_PROJECT_NAME]).credentials(new FakeGoogleCredentials()).build()
  private static final BASE_DESCRIPTION_2 = new BaseGoogleInstanceDescription(image: IMAGE_NAME, credentials: CREDENTIALS)
  private static final GOOGLE_APPLICATION_NAME = "test"
  private static final BASE_IMAGE_PROJECTS = ["centos-cloud", "ubuntu-os-cloud"]

  @Shared
  def taskMock

  def setupSpec() {
    this.taskMock = Mock(Task)
    TaskRepository.threadLocalTask.set(taskMock)
  }

  void "query source images should succeed"() {
    setup:
      def computeMock = new MockFor(Compute)
      def batchMock = new MockFor(BatchRequest)
      def imageProjects = [PROJECT_NAME] + BASE_IMAGE_PROJECTS
      def listMock = new MockFor(Compute.Images.List)

      def httpTransport = GoogleNetHttpTransport.newTrustedTransport()
      def jsonFactory = JacksonFactory.defaultInstance
      def httpRequestInitializer =
              new GoogleCredential.Builder().setTransport(httpTransport).setJsonFactory(jsonFactory).build()
      def images = new Compute.Builder(
              httpTransport, jsonFactory, httpRequestInitializer).setApplicationName(GOOGLE_APPLICATION_NAME).build().images()

      computeMock.demand.batch { new BatchRequest(httpTransport, httpRequestInitializer) }

      JsonBatchCallback<ImageList> callback = null

      for (def imageProject : imageProjects) {
        computeMock.demand.images { return images }
        listMock.demand.queue { imageListBatch, imageListCallback ->
          callback = imageListCallback
        }
      }

      def soughtImage = new Image(name: IMAGE_NAME)

      batchMock.demand.execute {
        def imageList = new ImageList()
        imageList.setItems([soughtImage])
        callback.onSuccess(imageList, null)
      }

    when:
      def sourceImage = null

      batchMock.use {
        computeMock.use {
          listMock.use {
            def compute = new Compute.Builder(
                    httpTransport, jsonFactory, httpRequestInitializer).setApplicationName(GOOGLE_APPLICATION_NAME).build()

            sourceImage = GCEUtil.querySourceImage(PROJECT_NAME, BASE_DESCRIPTION_1, compute, taskMock, PHASE, GOOGLE_APPLICATION_NAME, BASE_IMAGE_PROJECTS)
          }
        }
      }

    then:
      sourceImage == soughtImage
  }

  void "query source images should query configured imageProjects and succeed"() {
    setup:
      def computeMock = new MockFor(Compute)
      def batchMock = new MockFor(BatchRequest)
      def imageProjects = [PROJECT_NAME] + [IMAGE_PROJECT_NAME] + BASE_IMAGE_PROJECTS
      def listMock = new MockFor(Compute.Images.List)

      def httpTransport = GoogleNetHttpTransport.newTrustedTransport()
      def jsonFactory = JacksonFactory.defaultInstance
      def httpRequestInitializer =
              new GoogleCredential.Builder().setTransport(httpTransport).setJsonFactory(jsonFactory).build()
      def images = new Compute.Builder(
              httpTransport, jsonFactory, httpRequestInitializer).setApplicationName(GOOGLE_APPLICATION_NAME).build().images()

      computeMock.demand.batch { new BatchRequest(httpTransport, httpRequestInitializer) }

      JsonBatchCallback<ImageList> callback = null

      for (def imageProject : imageProjects) {
        computeMock.demand.images { return images }
        listMock.demand.queue { imageListBatch, imageListCallback ->
          callback = imageListCallback
        }
      }

      def soughtImage = new Image(name: IMAGE_NAME)

      batchMock.demand.execute {
        def imageList = new ImageList()
        imageList.setItems([soughtImage])
        callback.onSuccess(imageList, null)
      }

    when:
      def sourceImage = null

      batchMock.use {
        computeMock.use {
          listMock.use {
            def compute = new Compute.Builder(
                    httpTransport, jsonFactory, httpRequestInitializer).setApplicationName(GOOGLE_APPLICATION_NAME).build()

            sourceImage = GCEUtil.querySourceImage(PROJECT_NAME, BASE_DESCRIPTION_2, compute, taskMock, PHASE, GOOGLE_APPLICATION_NAME, BASE_IMAGE_PROJECTS)
          }
        }
      }

    then:
      sourceImage == soughtImage
  }

  void "query source images should fail"() {
    setup:
      def computeMock = new MockFor(Compute)
      def batchMock = new MockFor(BatchRequest)
      def imageProjects = [PROJECT_NAME] + BASE_IMAGE_PROJECTS
      def listMock = new MockFor(Compute.Images.List)

      def httpTransport = GoogleNetHttpTransport.newTrustedTransport()
      def jsonFactory = JacksonFactory.defaultInstance
      def httpRequestInitializer =
              new GoogleCredential.Builder().setTransport(httpTransport).setJsonFactory(jsonFactory).build()
      def images = new Compute.Builder(
              httpTransport, jsonFactory, httpRequestInitializer).setApplicationName(GOOGLE_APPLICATION_NAME).build().images()

      computeMock.demand.batch { new BatchRequest(httpTransport, httpRequestInitializer) }

      JsonBatchCallback<ImageList> callback = null

      for (def imageProject : imageProjects) {
        computeMock.demand.images { return images }
        listMock.demand.queue { imageListBatch, imageListCallback ->
          callback = imageListCallback
        }
      }

      batchMock.demand.execute {
        def imageList = new ImageList()
        imageList.setItems([new Image(name: IMAGE_NAME + "-WRONG")])
        callback.onSuccess(imageList, null)
      }

    when:
      def sourceImage = null

      batchMock.use {
        computeMock.use {
          listMock.use {
            def compute = new Compute.Builder(
                    httpTransport, jsonFactory, httpRequestInitializer).setApplicationName(GOOGLE_APPLICATION_NAME).build()

            sourceImage = GCEUtil.querySourceImage(PROJECT_NAME, BASE_DESCRIPTION_1, compute, taskMock, PHASE, GOOGLE_APPLICATION_NAME, BASE_IMAGE_PROJECTS)
          }
        }
      }

    then:
      thrown GoogleResourceNotFoundException
  }

  void "instance metadata with zero key-value pairs roundtrips properly"() {
    setup:
      def instanceMetadata = [:]

    when:
      def computeMetadata = GCEUtil.buildMetadataFromMap(instanceMetadata)
      def roundtrippedMetadata = GCEUtil.buildMapFromMetadata(computeMetadata)

    then:
      roundtrippedMetadata == instanceMetadata
  }

  void "instance metadata with exactly one key-value pair roundtrips properly"() {
    setup:
      def instanceMetadata = [someTestKey: "someTestValue"]

    when:
      def computeMetadata = GCEUtil.buildMetadataFromMap(instanceMetadata)
      def roundtrippedMetadata = GCEUtil.buildMapFromMetadata(computeMetadata)

    then:
      roundtrippedMetadata == instanceMetadata
  }

  void "instance metadata with more than one key-value pair roundtrips properly"() {
    setup:
      def instanceMetadata = [keyA: "valueA", keyB: "valueB", keyC: "valueC"]

    when:
      def computeMetadata = GCEUtil.buildMetadataFromMap(instanceMetadata)
      def roundtrippedMetadata = GCEUtil.buildMapFromMetadata(computeMetadata)

    then:
      roundtrippedMetadata == instanceMetadata
  }

  void "queryInstanceUrls should return matching instances from one zone"() {
    setup:
      def computeMock = Mock(Compute)
      def instancesMock = Mock(Compute.Instances)
      def instancesAggregatedListMock = Mock(Compute.Instances.AggregatedList)
      def zoneToInstancesMap = [
        "zones/asia-east1-a": new InstancesScopedList(),
        "zones/asia-east1-b": new InstancesScopedList(),
        "zones/asia-east1-c": new InstancesScopedList(),
        "zones/europe-west1-b": new InstancesScopedList(),
        "zones/europe-west1-c": new InstancesScopedList(),
        "zones/europe-west1-c": new InstancesScopedList(),
        "zones/us-central1-a": new InstancesScopedList(),
        "zones/us-central1-b": new InstancesScopedList(instances: [new Instance(name: INSTANCE_LOCAL_NAME_1, selfLink: INSTANCE_URL_1),
                                                                   new Instance(name: INSTANCE_LOCAL_NAME_2, selfLink: INSTANCE_URL_2)]),
        "zones/us-central1-c": new InstancesScopedList(),
        "zones/us-central1-f": new InstancesScopedList()
      ]
      def instanceAggregatedList = new InstanceAggregatedList(items: zoneToInstancesMap)

    when:
      def instanceUrls =
        GCEUtil.queryInstanceUrls(PROJECT_NAME, REGION, ["some-instance-name-1", "some-instance-name-2"], computeMock, taskMock, PHASE)

    then:
      1 * computeMock.instances() >> instancesMock
      1 * instancesMock.aggregatedList(PROJECT_NAME) >> instancesAggregatedListMock
      1 * instancesAggregatedListMock.execute() >> instanceAggregatedList
      instanceUrls == [INSTANCE_URL_1, INSTANCE_URL_2]
  }

  void "queryInstanceUrls should return matching instances from two zones"() {
    setup:
      def computeMock = Mock(Compute)
      def instancesMock = Mock(Compute.Instances)
      def instancesAggregatedListMock = Mock(Compute.Instances.AggregatedList)
      def zoneToInstancesMap = [
        "zones/asia-east1-a": new InstancesScopedList(),
        "zones/asia-east1-b": new InstancesScopedList(),
        "zones/asia-east1-c": new InstancesScopedList(),
        "zones/europe-west1-b": new InstancesScopedList(),
        "zones/europe-west1-c": new InstancesScopedList(),
        "zones/europe-west1-c": new InstancesScopedList(),
        "zones/us-central1-a": new InstancesScopedList(),
        "zones/us-central1-b": new InstancesScopedList(instances: [new Instance(name: INSTANCE_LOCAL_NAME_1, selfLink: INSTANCE_URL_1)]),
        "zones/us-central1-c": new InstancesScopedList(),
        "zones/us-central1-f": new InstancesScopedList(instances: [new Instance(name: INSTANCE_LOCAL_NAME_2, selfLink: INSTANCE_URL_2)])
      ]
      def instanceAggregatedList = new InstanceAggregatedList(items: zoneToInstancesMap)

    when:
      def instanceUrls =
        GCEUtil.queryInstanceUrls(PROJECT_NAME, REGION, ["some-instance-name-1", "some-instance-name-2"], computeMock, taskMock, PHASE)

    then:
      1 * computeMock.instances() >> instancesMock
      1 * instancesMock.aggregatedList(PROJECT_NAME) >> instancesAggregatedListMock
      1 * instancesAggregatedListMock.execute() >> instanceAggregatedList
      instanceUrls == [INSTANCE_URL_1, INSTANCE_URL_2]
  }

  void "queryInstanceUrls should throw exception when instance cannot be found"() {
    setup:
      def computeMock = Mock(Compute)
      def instancesMock = Mock(Compute.Instances)
      def instancesAggregatedListMock = Mock(Compute.Instances.AggregatedList)
      def zoneToInstancesMap = [
        "zones/asia-east1-a": new InstancesScopedList(),
        "zones/asia-east1-b": new InstancesScopedList(),
        "zones/asia-east1-c": new InstancesScopedList(),
        "zones/europe-west1-b": new InstancesScopedList(),
        "zones/europe-west1-c": new InstancesScopedList(),
        "zones/europe-west1-c": new InstancesScopedList(),
        "zones/us-central1-a": new InstancesScopedList(),
        "zones/us-central1-b": new InstancesScopedList(),
        "zones/us-central1-c": new InstancesScopedList(instances: [new Instance(name: INSTANCE_LOCAL_NAME_1, selfLink: INSTANCE_URL_1)]),
        "zones/us-central1-f": new InstancesScopedList()
      ]
      def instanceAggregatedList = new InstanceAggregatedList(items: zoneToInstancesMap)

    when:
      GCEUtil.queryInstanceUrls(PROJECT_NAME, REGION, ["some-instance-name-1", "some-instance-name-2"], computeMock, taskMock, PHASE)

    then:
      1 * computeMock.instances() >> instancesMock
      1 * instancesMock.aggregatedList(PROJECT_NAME) >> instancesAggregatedListMock
      1 * instancesAggregatedListMock.execute() >> instanceAggregatedList

      def exc = thrown GoogleResourceNotFoundException
      exc.message == "Instances [$INSTANCE_LOCAL_NAME_2] not found."
  }

  void "queryInstanceUrls should throw exception when instance is found but in a different region"() {
    setup:
      def computeMock = Mock(Compute)
      def instancesMock = Mock(Compute.Instances)
      def instancesAggregatedListMock = Mock(Compute.Instances.AggregatedList)
      def zoneToInstancesMap = [
        "zones/asia-east1-a": new InstancesScopedList(),
        "zones/asia-east1-b": new InstancesScopedList(),
        "zones/asia-east1-c": new InstancesScopedList(),
        "zones/europe-west1-b": new InstancesScopedList(instances: [new Instance(name: INSTANCE_LOCAL_NAME_1, selfLink: INSTANCE_URL_1)]),
        "zones/europe-west1-c": new InstancesScopedList(),
        "zones/europe-west1-c": new InstancesScopedList(instances: [new Instance(name: INSTANCE_LOCAL_NAME_2, selfLink: INSTANCE_URL_2)]),
        "zones/us-central1-a": new InstancesScopedList(),
        "zones/us-central1-b": new InstancesScopedList(),
        "zones/us-central1-c": new InstancesScopedList(),
        "zones/us-central1-f": new InstancesScopedList()
      ]
      def instanceAggregatedList = new InstanceAggregatedList(items: zoneToInstancesMap)

    when:
      GCEUtil.queryInstanceUrls(PROJECT_NAME, REGION, ["some-instance-name-1", "some-instance-name-2"], computeMock, taskMock, PHASE)

    then:
      1 * computeMock.instances() >> instancesMock
      1 * instancesMock.aggregatedList(PROJECT_NAME) >> instancesAggregatedListMock
      1 * instancesAggregatedListMock.execute() >> instanceAggregatedList

      def exc = thrown GoogleResourceNotFoundException
      exc.message == "Instances [$INSTANCE_LOCAL_NAME_1, $INSTANCE_LOCAL_NAME_2] not found."
  }

  @Unroll
  void "buildServiceAccount should return an empty list when either email or authScopes are unspecified"() {
    expect:
      GCEUtil.buildServiceAccount(serviceAccountEmail, authScopes) == []

    where:
      serviceAccountEmail                      | authScopes
      null                                     | ["some-scope"]
      ""                                       | ["some-scope"]
      "something@test.iam.gserviceaccount.com" | null
      "something@test.iam.gserviceaccount.com" | []
  }

  @Unroll
  void "buildServiceAccount should prepend base url if necessary"() {
    expect:
      GCEUtil.buildServiceAccount("default", authScopes) == expectedServiceAccount

    where:
      authScopes                                                   || expectedServiceAccount
      ["cloud-platform"]                                           || [new ServiceAccount(email: "default", scopes: ["https://www.googleapis.com/auth/cloud-platform"])]
      ["devstorage.read_only"]                                     || [new ServiceAccount(email: "default", scopes: ["https://www.googleapis.com/auth/devstorage.read_only"])]
      ["https://www.googleapis.com/auth/logging.write", "compute"] || [new ServiceAccount(email: "default", scopes: ["https://www.googleapis.com/auth/logging.write", "https://www.googleapis.com/auth/compute"])]
  }

  @Unroll
  void "calibrateTargetSizeWithAutoscaler should adjust target size to within autoscaler min/max range if necessary"() {
    when:
      def autoscalingPolicy = new GoogleAutoscalingPolicy(minNumReplicas: minNumReplicas, maxNumReplicas: maxNumReplicas)
      def description = new BasicGoogleDeployDescription(targetSize: origTargetSize, autoscalingPolicy: autoscalingPolicy)
      GCEUtil.calibrateTargetSizeWithAutoscaler(description)

    then:
      description.targetSize == expectedTargetSize

    where:
      origTargetSize | minNumReplicas | maxNumReplicas | expectedTargetSize
      3              | 5              | 7              | 5
      9              | 5              | 7              | 7
      6              | 5              | 7              | 6
  }

  @Unroll
  void "checkAllForwardingRulesExist should fail if any loadbalancers aren't found"() {
    setup:
      def application = "my-application"
      def task = Mock(Task)
      def phase = "BASE_PHASE"
      // Note: the findAll lets us use the @Unroll feature to make this test more compact.
      def forwardingRuleNames = [networkLB?.name, httpLB?.name].findAll { it != null }
      def loadBalancerProvider = Mock(GoogleLoadBalancerProvider)
      def loadBalancers = [networkLB, httpLB].findAll { it != null }
      def notFoundNames = ['bogus-name']
      loadBalancerProvider.getApplicationLoadBalancers("") >> loadBalancers

    when:
      def foundLoadBalancers = GCEUtil.queryAllLoadBalancers(loadBalancerProvider, forwardingRuleNames, task, phase)

    then:
      foundLoadBalancers.collect { it.name } == forwardingRuleNames

    when:
      foundLoadBalancers = GCEUtil.queryAllLoadBalancers(loadBalancerProvider, forwardingRuleNames + 'bogus-name', task, phase)

    then:
      def resourceNotFound = thrown(GoogleResourceNotFoundException)
      def msg = "Load balancers $notFoundNames not found."
      resourceNotFound.message == msg.toString()

    where:
      networkLB                                         | httpLB
      new GoogleLoadBalancer(name: "network").getView() | new GoogleHttpLoadBalancer(name: "http").getView()
      null                                              | new GoogleHttpLoadBalancer(name: "http").getView()
      new GoogleLoadBalancer(name: "network").getView() | null
      null                                              | null
  }

  @Unroll
  void "should add http load balancer backend if metadata exists"() {
    setup:
      def loadBalancerNameList = lbNames
      def serverGroup =
        new GoogleServerGroup(
          name: 'application-derp-v000',
          region: REGION,
          regional: isRegional,
          zone: ZONE,
          asg: [
            (GoogleServerGroup.View.GLOBAL_LOAD_BALANCER_NAMES): loadBalancerNameList,
          ],
          launchConfig: [
            instanceTemplate: new InstanceTemplate(name: "irrelevant-instance-template-name",
              properties: [
                'metadata': new Metadata(items: [
                  new Metadata.Items(
                    key: (GoogleServerGroup.View.LOAD_BALANCING_POLICY),
                    value: "{\"balancingMode\": \"UTILIZATION\",\"maxUtilization\": 0.80, \"listeningPort\": 8080, \"capacityScaler\": 0.77}"
                  ),
                  new Metadata.Items(
                    key: (GoogleServerGroup.View.BACKEND_SERVICE_NAMES),
                    value: backendServiceNames
                  )
                ])
              ])
          ]).view
      def computeMock = Mock(Compute)
      def backendServicesMock = Mock(Compute.BackendServices)
      def backendSvcGetMock = Mock(Compute.BackendServices.Get)
      def backendUpdateMock = Mock(Compute.BackendServices.Update)
      def googleLoadBalancerProviderMock = Mock(GoogleLoadBalancerProvider)
      googleLoadBalancerProviderMock.getApplicationLoadBalancers("") >> loadBalancerList
      def task = Mock(Task)
      def bs = new BackendService(backends: [])
      if (lbNames) {
        serverGroup.launchConfig.instanceTemplate.properties.metadata.items.add(
          new Metadata.Items(
            key: (GoogleServerGroup.View.GLOBAL_LOAD_BALANCER_NAMES),
            value: lbNames.join(",").trim()
          )
        )
      }

    when:
      GCEUtil.addHttpLoadBalancerBackends(computeMock, new ObjectMapper(), PROJECT_NAME, serverGroup,
        googleLoadBalancerProviderMock, task, "PHASE")

    then:
      _ * computeMock.backendServices() >> backendServicesMock
      _ * backendServicesMock.get(PROJECT_NAME, 'backend-service') >> backendSvcGetMock
      _ * backendSvcGetMock.execute() >> bs
      _ * backendServicesMock.update(PROJECT_NAME, 'backend-service', bs) >> backendUpdateMock
      _ * backendUpdateMock.execute()
      bs.backends.size == lbNames.size

    where:
      isRegional | location | loadBalancerList                                                         | lbNames                          | backendServiceNames
      false      | ZONE     |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer'] | 'backend-service'
      true       | REGION   |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer'] | 'backend-service'
      false      | ZONE     |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer'] | 'backend-service'
      true       | REGION   |  [new GoogleHttpLoadBalancer(name: 'spinnaker-http-load-balancer').view] | ['spinnaker-http-load-balancer'] | 'backend-service'
      false      | ZONE     |  []                                                                      | []                               | null
      true       | REGION   |  []                                                                      | []                               | null
  }

  void "should update url map if any of the services are different"() {
    setup:
      def description = new UpsertGoogleLoadBalancerDescription(
        accountName: ACCOUNT_NAME,
        "loadBalancerName": LOAD_BALANCER_NAME,
        "portRange": PORT_RANGE,
        "defaultService": new GoogleBackendService(
          name: newDefault,
          backends: [],
          healthCheck: null,
        ),
        "certificate": "",
        "hostRules": [
          new GoogleHostRule(
            hostPatterns: newHosts,
            pathMatcher: new GooglePathMatcher(
              pathRules: [
                new GooglePathRule(
                  paths: newPaths,
                  backendService: new GoogleBackendService(
                    name: newService,
                    backends: [],
                    healthCheck: null,
                  )
                )
              ]
            )
          )
        ]
      )
      def urlMap = new UrlMap(
        defaultService: oldDefault,
        hostRules: [
            new HostRule(
              pathMatcher: "pmName",
              hosts: oldHosts,
            )
        ],
        pathMatchers: [
          new PathMatcher(
            name: "pmName",
            pathRules: [
                new PathRule(
                  paths: oldPaths,
                  service: oldService,
                )
            ]
          )
        ],
      )

    when:
      def result = GCEUtil.shouldUpdateUrlMap(urlMap, description)

    then:
      result == shouldUpdate

    where:
      oldDefault | newDefault | oldHosts  | oldPaths | oldService   | newHosts           | newPaths         | newService   | shouldUpdate
      'derp'     | 'derp'     | ['a.com'] | ['/old'] | 'oldService' | ['a.com']          | ['/old']         | 'oldService' | false
      'derp'     | 'nope'     | ['a.com'] | ['/old'] | 'oldService' | ['a.com']          | ['/old']         | 'oldService' | true
      'derp'     | 'derp'     | ['a.com'] | ['/old'] | 'oldService' | ['b.com']          | ['/old']         | 'oldService' | true
      'derp'     | 'derp'     | ['a.com'] | ['/old'] | 'oldService' | ['a.com']          | ['/new']         | 'oldService' | true
      'derp'     | 'derp'     | ['a.com'] | ['/old'] | 'oldService' | ['a.com']          | ['/old']         | 'newService' | true
      'derp'     | 'derp'     | ['a.com'] | ['/old'] | 'oldService' | ['a.com']          | ['/new']         | 'oldService' | true
      'derp'     | 'derp'     | ['a.com'] | ['/old'] | 'oldService' | ['a.com']          | ['/old', '/new'] | 'oldService' | true
      'derp'     | 'derp'     | ['a.com'] | ['/old'] | 'oldService' | ['a.com', 'b.com'] | ['/old']         | 'oldService' | true
  }
}

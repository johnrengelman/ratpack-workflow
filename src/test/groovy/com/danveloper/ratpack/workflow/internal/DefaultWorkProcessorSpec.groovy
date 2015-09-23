package com.danveloper.ratpack.workflow.internal

import com.danveloper.ratpack.workflow.*
import com.google.common.io.ByteSource
import ratpack.config.ConfigData
import ratpack.registry.Registry
import ratpack.test.exec.ExecHarness
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DefaultWorkProcessorSpec extends Specification {

  @AutoCleanup
  ExecHarness execHarness = ExecHarness.harness(Runtime.getRuntime().availableProcessors())

  def json = """
    {
      "name": "AWESOMEWORKFLOW-001-EL8_MODE",
      "description": "My really awesome workflow!",
      "tags": {
        "stack": "prod",
        "phase": "build"
      },
      "works": [
        {
          "type": "foo",
          "version": "1.0",
          "data": {
            "foo": "bar"
          }
        },
        {
          "type": "bar",
          "version": "1.0",
          "data": {
            "direction": "up"
          }
        }
      ]
    }
  """
  def configData = ConfigData.of { d ->
    d.json(ByteSource.wrap(json.bytes)).build()
  }

  void "should invoke all work for a given description and chain"() {
    setup:
    CountDownLatch latch = new CountDownLatch(3)
    DefaultWorkChain chain = new DefaultWorkChain(Registry.empty())
        .work("foo", "1.0") { ctx ->
          latch.countDown()
          ctx.next()
        }
        .work("bar", "1.0") { ctx ->
          latch.countDown()
          ctx.next()
        }
        .all { ctx ->
          latch.countDown()
          ctx.complete()
        }
    WorkStatusRepository workStatusRepository = new InMemoryWorkStatusRepository()
    FlowStatusRepository flowStatusRepository = new InMemoryFlowStatusRepository(workStatusRepository)
    DefaultWorkProcessor processor = new DefaultWorkProcessor(chain.works as Work[], workStatusRepository, flowStatusRepository)

    when:
    execHarness.run {
      processor.onStart(null)
      flowStatusRepository.create(FlowConfigSource.of(configData)).then { flowStatus ->
        processor.start(flowStatus).operation().then()
      }
    }

    and:
    latch.await(10, TimeUnit.SECONDS)

    then:
    0l == latch.count
  }

  void "should fail a flow if one of the works has failed"() {
    setup:
    AtomicInteger adder = new AtomicInteger()
    DefaultWorkChain chain = new DefaultWorkChain(Registry.empty())
    .work("foo", "1.0") { ctx ->
      throw new RuntimeException("!!")
    }
    .work("bar", "1.0") { ctx ->
      adder.incrementAndGet()
      ctx.next()
    }
    .all { ctx ->
      adder.incrementAndGet()
    }
    WorkStatusRepository workStatusRepository = new InMemoryWorkStatusRepository()
    FlowStatusRepository flowStatusRepository = new InMemoryFlowStatusRepository(workStatusRepository)
    DefaultWorkProcessor processor = new DefaultWorkProcessor(chain.works as Work[], workStatusRepository, flowStatusRepository)

    when:
    execHarness.run {
      processor.onStart(null)
      flowStatusRepository.create(FlowConfigSource.of(configData)).then { flowStatus ->
        processor.start(flowStatus).operation().then()
      }
    }

    and:
    execHarness.controller.eventLoopGroup.awaitTermination(5, TimeUnit.SECONDS)

    and:
    def flows = execHarness.yield {
      flowStatusRepository.list()
    }.valueOrThrow

    then:
    0 == adder.get()
    1 == flows.size()
    flows[0].state == WorkState.FAILED
    flows[0].works[0].state == WorkState.FAILED
    flows[0].works[0].error.message == "!!"
    flows[0].works[1].state == WorkState.NOT_STARTED
  }
}

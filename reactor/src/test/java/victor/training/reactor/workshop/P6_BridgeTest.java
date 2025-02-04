package victor.training.reactor.workshop;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer.MethodName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import victor.training.reactor.lite.Utils;
import victor.training.reactor.workshop.P6_Bridge.Dependency;
import victor.training.reactor.workshop.P6_Bridge.ResponseMessage;
import victor.training.reactor.workshop.P6_Bridge.User;
import victor.training.util.CaptureSystemOutputExtension;
import victor.training.util.RunAsNonBlocking;
import victor.training.util.SubscribedProbe;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.time.Duration.ofMillis;
import static java.util.concurrent.CompletableFuture.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
@TestMethodOrder(MethodName.class)
@ExtendWith(MockitoExtension.class)
public class P6_BridgeTest {
  @Mock
  Dependency dependency;
  @InjectMocks
  protected P6_Bridge workshop;

  @RegisterExtension
  SubscribedProbe subscribed = new SubscribedProbe();
  @RegisterExtension
  CaptureSystemOutputExtension systemOutput = new CaptureSystemOutputExtension();

  private User user = new User();

  @Test
  void p01_blockForMono() {
    Mono<String> mono = Mono.just("MESSAGE").delayElement(ofMillis(500));
    when(dependency.save("IN")).thenReturn(subscribed.once(mono));
    workshop.p01_blockForMono("IN");
    assertThat(systemOutput.toString()).contains("MESSAGE");
  }

  @Test
  void p02_blockForFlux() {
    Flux<String> flux = Flux.interval(ofMillis(100)).map(i -> ""+i).take(10);
    when(dependency.findAll()).thenReturn(flux);
    workshop.p02_blockForFlux();
    assertThat(systemOutput.toString()).contains("0, 1, 2, 3, 4, 5, 6, 7, 8, 9");
  }

  @Test
  void p03_blockingCalls() {
    when(dependency.legacyBlockingCall()).thenAnswer(p-> {
      Utils.sleep(200);
      return "data";
    });
    Mono<String> mono = RunAsNonBlocking.nonBlocking(() -> workshop.p03_blockingCalls());
    assertThat(mono.block()).isEqualTo("data");
  }

  @Test
  void p04_fromMonoToCompletableFuture() throws ExecutionException, InterruptedException {
    CompletableFuture<User> future = workshop.p04_fromMonoToCompletableFuture(Mono.just(user));
    assertThat(future.get()).isEqualTo(user);
  }

  @Test
  void p05_fromCompletableFutureToMono() {
    Mono<User> mono = workshop.p05_fromCompletableFutureToMono(completedFuture(user));
    assertThat(mono.block()).isEqualTo(user);
  }

  @Test
  @Timeout(value = 500, unit = MILLISECONDS)
  void p06_callback() {
    ResponseMessage responseMessage = new ResponseMessage();
    Mono<ResponseMessage> mono = workshop.p06_callback(1L);
    runAsync(() -> workshop.p06_receiveResponseOnReplyQueue(1L, responseMessage),
            delayedExecutor(200, MILLISECONDS))
            .exceptionally(ex-> {
              log.error("Exception in callback: " + ex, ex);
              return null;
            });
    ResponseMessage r = mono.block();
    assertThat(r).isEqualTo(responseMessage);
    verify(dependency).sendMessageOnQueueBlocking(1L);
  }


  @Test
  @Timeout(1)
  void p07_fluxOfSignals() {
    ResponseMessage responseMessage = new ResponseMessage();
    runAsync(() -> workshop.p07_externalSignal(10), delayedExecutor(200, MILLISECONDS));
    assertThat(workshop.p07_fluxOfSignals().blockFirst()).isEqualTo(10);
  }

  @Test
  @Timeout(1)
  void p07_fluxOfSignals_two() {
    ResponseMessage responseMessage = new ResponseMessage();
    runAsync(() -> workshop.p07_externalSignal(10), delayedExecutor(200, MILLISECONDS));
    runAsync(() -> workshop.p07_externalSignal(20), delayedExecutor(400, MILLISECONDS));
    workshop.p07_fluxOfSignals()
            .timeout(ofMillis(600))
            .as(StepVerifier::create)
            .expectNext(10, 20)
            .verifyTimeout(ofMillis(550));
  }


}

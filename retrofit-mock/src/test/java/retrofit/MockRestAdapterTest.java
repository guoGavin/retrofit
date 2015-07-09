// Copyright 2013 Square, Inc.
package retrofit;

import com.squareup.okhttp.Response;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import retrofit.http.GET;
import rx.Observable;
import rx.functions.Action1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static retrofit.Utils.SynchronousExecutor;

public class MockRetrofitTest {
  interface SyncExample {
    @GET("/") Object doStuff();
  }

  interface AsyncExample {
    @GET("/") void doStuff(Callback<String> cb);
  }

  interface AsyncCallbackSubtypeExample {
    abstract class Foo implements Callback<String> {}

    @GET("/") void doStuff(Foo foo);
  }

  interface ObservableExample {
    @GET("/") Observable<String> doStuff();
  }

  private Executor httpExecutor;
  private Executor callbackExecutor;
  private MockRetrofit mockRetrofit;
  private Throwable nextError;

  @Before public void setUp() throws IOException {
    httpExecutor = spy(new SynchronousExecutor());
    callbackExecutor = spy(new SynchronousExecutor());

    RestAdapter restAdapter = new RestAdapter.Builder() //
        .callbackExecutor(callbackExecutor)
        .baseUrl("http://example.com")
        .errorHandler(new ErrorHandler() {
          @Override public Throwable handleError(RetrofitError cause) {
            if (nextError != null) {
              Throwable error = nextError;
              nextError = null;
              return error;
            }
            return cause;
          }
        })
        .build();

    mockRetrofit = MockRetrofit.from(restAdapter, httpExecutor);

    // Seed the random with a value so the tests are deterministic.
    mockRetrofit.random.setSeed(2847);
  }

  @Test public void delayRestrictsRange() {
    try {
      mockRetrofit.setDelay(-1);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Delay must be positive value.");
    }
    try {
      mockRetrofit.setDelay(Long.MAX_VALUE);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageStartingWith("Delay value too large.");
    }
  }

  @Test public void varianceRestrictsRange() {
    try {
      mockRetrofit.setVariancePercentage(-13);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Variance percentage must be between 0 and 100.");
    }
    try {
      mockRetrofit.setVariancePercentage(174);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Variance percentage must be between 0 and 100.");
    }
  }

  @Test public void errorRestrictsRange() {
    try {
      mockRetrofit.setErrorPercentage(-13);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Error percentage must be between 0 and 100.");
    }
    try {
      mockRetrofit.setErrorPercentage(174);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Error percentage must be between 0 and 100.");
    }
  }

  @Test public void errorPercentageIsAccurate() {
    mockRetrofit.setErrorPercentage(0);
    for (int i = 0; i < 10000; i++) {
      assertThat(mockRetrofit.calculateIsFailure()).isFalse();
    }

    mockRetrofit.setErrorPercentage(3);
    int failures = 0;
    for (int i = 0; i < 100000; i++) {
      if (mockRetrofit.calculateIsFailure()) {
        failures += 1;
      }
    }
    assertThat(failures).isEqualTo(2964); // ~3% of 100k
  }

  @Test public void delayVarianceIsAccurate() {
    mockRetrofit.setDelay(2000);

    mockRetrofit.setVariancePercentage(0);
    for (int i = 0; i < 100000; i++) {
      assertThat(mockRetrofit.calculateDelayForCall()).isEqualTo(2000);
    }

    mockRetrofit.setVariancePercentage(40);
    int lowerBound = Integer.MAX_VALUE;
    int upperBound = Integer.MIN_VALUE;
    for (int i = 0; i < 100000; i++) {
      int delay = mockRetrofit.calculateDelayForCall();
      if (delay > upperBound) {
        upperBound = delay;
      }
      if (delay < lowerBound) {
        lowerBound = delay;
      }
    }
    assertThat(upperBound).isEqualTo(2799); // ~40% above 2000
    assertThat(lowerBound).isEqualTo(1200); // ~40% below 2000
  }

  @Test public void errorVarianceIsAccurate() {
    mockRetrofit.setDelay(2000);

    int lowerBound = Integer.MAX_VALUE;
    int upperBound = Integer.MIN_VALUE;
    for (int i = 0; i < 100000; i++) {
      int delay = mockRetrofit.calculateDelayForError();
      if (delay > upperBound) {
        upperBound = delay;
      }
      if (delay < lowerBound) {
        lowerBound = delay;
      }
    }
    assertThat(upperBound).isEqualTo(5999); // 3 * 2000
    assertThat(lowerBound).isEqualTo(0);
  }

  @Test public void syncFailureTriggersNetworkError() {
    mockRetrofit.setErrorPercentage(100);
    mockRetrofit.setDelay(1);

    class MockSyncExample implements SyncExample {
      @Override public Object doStuff() {
        throw new AssertionError();
      }
    }

    SyncExample mockService = mockRetrofit.create(SyncExample.class, new MockSyncExample());

    try {
      mockService.doStuff();
      fail();
    } catch (RetrofitError e) {
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.NETWORK);
      assertThat(e.getCause()).hasMessage("Mock network error!");
    }
  }

  @Test public void asyncFailureTriggersNetworkError() {
    mockRetrofit.setDelay(1);
    mockRetrofit.setErrorPercentage(100);

    class MockAsyncExample implements AsyncExample {
      @Override public void doStuff(Callback<String> cb) {
        throw new AssertionError();
      }
    }

    AsyncExample mockService = mockRetrofit.create(AsyncExample.class, new MockAsyncExample());

    final AtomicReference<RetrofitError> errorRef = new AtomicReference<>();
    mockService.doStuff(new Callback<String>() {
      @Override public void success(String o, Response response) {
        throw new AssertionError();
      }

      @Override public void failure(RetrofitError error) {
        errorRef.set(error);
      }
    });

    verify(httpExecutor).execute(any(Runnable.class));
    verify(callbackExecutor).execute(any(Runnable.class));

    RetrofitError error = errorRef.get();
    assertThat(error.getKind()).isEqualTo(RetrofitError.Kind.NETWORK);
    assertThat(error.getCause()).hasMessage("Mock network error!");
  }

  @Test public void syncApiIsCalledWithDelay() {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    final AtomicBoolean called = new AtomicBoolean();
    final Object expected = new Object();
    class MockSyncExample implements SyncExample {
      @Override public Object doStuff() {
        called.set(true);
        return expected;
      }
    }

    SyncExample mockService = mockRetrofit.create(SyncExample.class, new MockSyncExample());

    long startNanos = System.nanoTime();
    Object actual = mockService.doStuff();
    long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

    assertThat(called.get()).isTrue();
    assertThat(actual).isEqualTo(expected);
    assertThat(tookMs).isGreaterThanOrEqualTo(100);
  }

  @Test public void asyncApiIsCalledWithDelay() {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    @SuppressWarnings("RedundantStringConstructorCall") // Allocated on-heap.
    final String expected = new String("Hi");

    class MockAsyncExample implements AsyncExample {
      @Override public void doStuff(Callback<String> cb) {
        cb.success(expected, null);
      }
    }

    AsyncExample mockService = mockRetrofit.create(AsyncExample.class, new MockAsyncExample());

    final long startNanos = System.nanoTime();
    final AtomicLong tookMs = new AtomicLong();
    final AtomicReference<Object> actual = new AtomicReference<>();
    mockService.doStuff(new Callback<String>() {
      @Override public void success(String result, Response response) {
        tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        actual.set(result);
      }

      @Override public void failure(RetrofitError error) {
        throw new AssertionError();
      }
    });

    verify(httpExecutor).execute(any(Runnable.class));
    verify(callbackExecutor).execute(any(Runnable.class));

    assertThat(actual.get()).isNotNull().isSameAs(expected);
    assertThat(tookMs.get()).isGreaterThanOrEqualTo(100);
  }

  @Test public void observableApiIsCalledWithDelay() {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    @SuppressWarnings("RedundantStringConstructorCall") // Allocated on-heap.
    final String expected = new String("Hello");

    class MockObservableExample implements ObservableExample {
      @Override public Observable<String> doStuff() {
        return Observable.just(expected);
      }
    }

    ObservableExample mockService =
        mockRetrofit.create(ObservableExample.class, new MockObservableExample());

    final long startNanos = System.nanoTime();
    final AtomicLong tookMs = new AtomicLong();
    final AtomicReference<Object> actual = new AtomicReference<>();
    Action1<Object> onSuccess = new Action1<Object>() {
      @Override public void call(Object o) {
        tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        actual.set(o);
      }
    };
    Action1<Throwable> onError = new Action1<Throwable>() {
      @Override public void call(Throwable throwable) {
        throw new AssertionError();
      }
    };

    mockService.doStuff().subscribe(onSuccess, onError);

    verify(httpExecutor, atLeastOnce()).execute(any(Runnable.class));
    verifyZeroInteractions(callbackExecutor);

    assertThat(actual.get()).isNotNull().isSameAs(expected);
    assertThat(tookMs.get()).isGreaterThanOrEqualTo(100);
  }


  @Test public void syncHttpExceptionBecomesError() {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    @SuppressWarnings("RedundantStringConstructorCall") // Allocated on-heap.
    final String expected = new String("Hello");

    class MockSyncExample implements SyncExample {
      @Override public String doStuff() {
        throw new MockHttpException(404, "Not Found", expected);
      }
    }

    SyncExample mockService = mockRetrofit.create(SyncExample.class, new MockSyncExample());

    long startNanos = System.nanoTime();
    try {
      mockService.doStuff();
      fail();
    } catch (RetrofitError e) {
      long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      assertThat(tookMs).isGreaterThanOrEqualTo(100);
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.HTTP);
      assertThat(e.getResponse().code()).isEqualTo(404);
      assertThat(e.getResponse().message()).isEqualTo("Not Found");
      assertThat(e.getBody()).isSameAs(expected);
      assertThat(e.getSuccessType()).isEqualTo(Object.class);
    }
  }

  @Test public void asyncHttpExceptionBecomesError() {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    @SuppressWarnings("RedundantStringConstructorCall") // Allocated on-heap.
    final String expected = new String("Greetings");

    class MockAsyncExample implements AsyncExample {
      @Override public void doStuff(Callback<String> cb) {
        throw new MockHttpException(404, "Not Found", expected);
      }
    }

    AsyncExample mockService = mockRetrofit.create(AsyncExample.class, new MockAsyncExample());

    final long startNanos = System.nanoTime();
    final AtomicLong tookMs = new AtomicLong();
    final AtomicReference<RetrofitError> errorRef = new AtomicReference<>();
    mockService.doStuff(new Callback<String>() {
      @Override public void success(String o, Response response) {
        throw new AssertionError();
      }

      @Override public void failure(RetrofitError error) {
        tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        errorRef.set(error);
      }
    });

    verify(httpExecutor).execute(any(Runnable.class));
    verify(callbackExecutor).execute(any(Runnable.class));

    RetrofitError error = errorRef.get();
    assertThat(tookMs.get()).isGreaterThanOrEqualTo(100);
    assertThat(error.getKind()).isEqualTo(RetrofitError.Kind.HTTP);
    assertThat(error.getResponse().code()).isEqualTo(404);
    assertThat(error.getResponse().message()).isEqualTo("Not Found");
    assertThat(error.getBody()).isSameAs(expected);
    assertThat(error.getSuccessType()).isEqualTo(String.class);
  }

  @Test public void observableHttpExceptionBecomesError() {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    @SuppressWarnings("RedundantStringConstructorCall") // Allocated on-heap.
    final String expected = new String("Hi");

    class MockObservableExample implements ObservableExample {
      @Override public Observable<String> doStuff() {
        throw new MockHttpException(404, "Not Found", expected);
      }
    }

    ObservableExample mockService =
        mockRetrofit.create(ObservableExample.class, new MockObservableExample());

    final long startNanos = System.nanoTime();
    final AtomicLong tookMs = new AtomicLong();
    final AtomicReference<RetrofitError> errorRef = new AtomicReference<>();
    mockService.doStuff().subscribe(new Action1<Object>() {
      @Override public void call(Object o) {
        throw new AssertionError();
      }
    }, new Action1<Throwable>() {
      @Override public void call(Throwable error) {
        assertThat(error).isInstanceOf(RetrofitError.class);
        tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        errorRef.set((RetrofitError) error);
      }
    });

    verify(httpExecutor).execute(any(Runnable.class));
    verifyZeroInteractions(callbackExecutor);

    RetrofitError error = errorRef.get();
    assertThat(tookMs.get()).isGreaterThanOrEqualTo(100);
    assertThat(error.getKind()).isEqualTo(RetrofitError.Kind.HTTP);
    assertThat(error.getResponse().code()).isEqualTo(404);
    assertThat(error.getResponse().message()).isEqualTo("Not Found");
    assertThat(error.getBody()).isSameAs(expected);
    assertThat(error.getSuccessType()).isEqualTo(String.class);
  }

  @Test public void nullBodyIsAllowedOnHttpException() throws Exception {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    class MockObservableExample implements ObservableExample {
      @Override public Observable<String> doStuff() {
        throw MockHttpException.newBadRequest(null);
      }
    }

    ObservableExample mockService =
        mockRetrofit.create(ObservableExample.class, new MockObservableExample());

    final long startNanos = System.nanoTime();
    final AtomicLong tookMs = new AtomicLong();
    final AtomicReference<RetrofitError> errorRef = new AtomicReference<>();
    mockService.doStuff().subscribe(new Action1<Object>() {
      @Override public void call(Object o) {
        throw new AssertionError();
      }
    }, new Action1<Throwable>() {
      @Override public void call(Throwable error) {
        assertThat(error).isInstanceOf(RetrofitError.class);
        tookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        errorRef.set((RetrofitError) error);
      }
    });

    verify(httpExecutor).execute(any(Runnable.class));
    verifyZeroInteractions(callbackExecutor);

    RetrofitError error = errorRef.get();
    assertThat(tookMs.get()).isGreaterThanOrEqualTo(100);
    assertThat(error.getKind()).isEqualTo(RetrofitError.Kind.HTTP);
    assertThat(error.getResponse().code()).isEqualTo(400);
    assertThat(error.getResponse().message()).isEqualTo("Bad Request");
    assertThat(error.getBody()).isNull();
    assertThat(error.getSuccessType()).isEqualTo(String.class);
  }

  @Test public void syncErrorUsesErrorHandler() {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    class MockSyncExample implements SyncExample {
      @Override public Object doStuff() {
        throw MockHttpException.newNotFound(new Object());
      }
    }

    SyncExample mockService = mockRetrofit.create(SyncExample.class, new MockSyncExample());
    nextError = new IllegalArgumentException("Test");

    try {
      mockService.doStuff();
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Test");
    }
  }

  @Test public void asyncErrorUsesErrorHandler() throws InterruptedException {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    class MockAsyncExample implements AsyncExample {
      @Override public void doStuff(Callback<String> cb) {
        throw MockHttpException.newNotFound(new Object());
      }
    }

    AsyncExample mockService = mockRetrofit.create(AsyncExample.class, new MockAsyncExample());
    nextError = new IllegalArgumentException("Test");

    final CountDownLatch latch = new CountDownLatch(1);
    mockService.doStuff(new Callback<String>() {
      @Override public void success(String o, Response response) {
        throw new AssertionError();
      }

      @Override public void failure(RetrofitError error) {
        assertThat(error.getCause()).hasMessage("Test");
        latch.countDown();
      }
    });
    assertTrue(latch.await(1, TimeUnit.SECONDS));
  }

  @Test public void observableErrorUsesErrorHandler() throws InterruptedException {
    mockRetrofit.setDelay(100);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    class MockObservableExample implements ObservableExample {
      @Override public Observable<String> doStuff() {
        throw MockHttpException.newNotFound(new Object());
      }
    }

    ObservableExample mockService =
        mockRetrofit.create(ObservableExample.class, new MockObservableExample());
    nextError = new IllegalArgumentException("Test");

    final CountDownLatch latch = new CountDownLatch(1);
    mockService.doStuff().subscribe(new Action1<Object>() {
      @Override public void call(Object o) {
        throw new AssertionError();
      }
    }, new Action1<Throwable>() {
      @Override public void call(Throwable error) {
        assertThat(error).hasMessage("Test");
        latch.countDown();
      }
    });
    assertTrue(latch.await(1, TimeUnit.SECONDS));
  }

  @Test public void asyncCanUseCallbackSubtype() {
    mockRetrofit.setDelay(1);
    mockRetrofit.setVariancePercentage(0);
    mockRetrofit.setErrorPercentage(0);

    class MockAsyncCallbackSubtypeExample implements AsyncCallbackSubtypeExample {
      @Override public void doStuff(Foo foo) {
        foo.success("Hello!", null);
      }
    }

    AsyncCallbackSubtypeExample mockService =
        mockRetrofit.create(AsyncCallbackSubtypeExample.class,
            new MockAsyncCallbackSubtypeExample());

    final AtomicReference<String> actual = new AtomicReference<>();
    mockService.doStuff(new AsyncCallbackSubtypeExample.Foo() {
      @Override public void success(String result, Response response) {
        actual.set(result);
      }

      @Override public void failure(RetrofitError error) {
        throw new AssertionError();
      }
    });

    assertThat(actual.get()).isNotNull().isEqualTo("Hello!");
  }
}

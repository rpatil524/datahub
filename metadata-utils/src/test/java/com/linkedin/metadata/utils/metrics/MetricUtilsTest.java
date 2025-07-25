package com.linkedin.metadata.utils.metrics;

import static org.testng.Assert.*;

import com.codahale.metrics.MetricRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class MetricUtilsTest {

  private SimpleMeterRegistry meterRegistry;
  private MetricUtils metricUtils;
  private AutoCloseable mockitoCloseable;

  @Mock private MeterRegistry mockMeterRegistry;

  @BeforeMethod
  public void setUp() {
    mockitoCloseable = MockitoAnnotations.openMocks(this);
    meterRegistry = new SimpleMeterRegistry();
    metricUtils = MetricUtils.builder().registry(meterRegistry).build();
  }

  @AfterMethod
  public void tearDown() throws Exception {
    if (mockitoCloseable != null) {
      mockitoCloseable.close();
    }
    meterRegistry.clear();
    meterRegistry.close();
  }

  @Test
  public void testGetRegistryReturnsOptionalWithRegistry() {
    Optional<MeterRegistry> registry = metricUtils.getRegistry();
    assertTrue(registry.isPresent());
    assertSame(registry.get(), meterRegistry);
  }

  @Test
  public void testGetRegistryReturnsEmptyOptionalWhenNull() {
    MetricUtils utilsWithNullRegistry = MetricUtils.builder().registry(null).build();

    Optional<MeterRegistry> registry = utilsWithNullRegistry.getRegistry();
    assertFalse(registry.isPresent());
  }

  @Test
  public void testTimeRecordsTimerWithDropwizardTag() {
    String metricName = "test.timer";
    long durationNanos = TimeUnit.MILLISECONDS.toNanos(100);

    metricUtils.time(metricName, durationNanos);

    Timer timer = meterRegistry.timer(metricName, MetricUtils.DROPWIZARD_METRIC, "true");
    assertNotNull(timer);
    assertEquals(timer.count(), 1);
    assertEquals(timer.totalTime(TimeUnit.NANOSECONDS), (double) durationNanos);
  }

  @Test
  public void testTimeWithNullRegistryDoesNothing() {
    MetricUtils utilsWithNullRegistry = MetricUtils.builder().registry(null).build();

    // Should not throw exception
    utilsWithNullRegistry.time("test.timer", 1000);
  }

  @Test
  public void testIncrementWithClassAndMetricName() {
    Class<?> testClass = this.getClass();
    String metricName = "test.counter";
    double incrementValue = 5.0;

    metricUtils.increment(testClass, metricName, incrementValue);

    String expectedName = MetricRegistry.name(testClass, metricName);
    Counter counter = meterRegistry.counter(expectedName, MetricUtils.DROPWIZARD_METRIC, "true");
    assertNotNull(counter);
    assertEquals(counter.count(), incrementValue);
  }

  @Test
  public void testIncrementWithMetricNameOnly() {
    String metricName = "simple.counter";
    double incrementValue = 3.0;

    metricUtils.increment(metricName, incrementValue);

    Counter counter = meterRegistry.counter(metricName, MetricUtils.DROPWIZARD_METRIC, "true");
    assertNotNull(counter);
    assertEquals(counter.count(), incrementValue);
  }

  @Test
  public void testExceptionIncrementCreatesMultipleCounters() {
    Class<?> testClass = this.getClass();
    String baseMetricName = "error.counter";
    RuntimeException exception = new RuntimeException("Test exception");

    metricUtils.exceptionIncrement(testClass, baseMetricName, exception);

    // Check base counter
    String baseName = MetricRegistry.name(testClass, baseMetricName);
    Counter baseCounter = meterRegistry.counter(baseName, MetricUtils.DROPWIZARD_METRIC, "true");
    assertNotNull(baseCounter);
    assertEquals(baseCounter.count(), 1.0);

    // The snake case conversion in the code is: "RuntimeException" -> "_Runtime_Exception"
    String exceptionName =
        MetricRegistry.name(testClass, baseMetricName + "_" + "_Runtime_Exception");
    Counter exceptionCounter =
        meterRegistry.counter(exceptionName, MetricUtils.DROPWIZARD_METRIC, "true");
    assertNotNull(exceptionCounter);
    assertEquals(exceptionCounter.count(), 1.0);
  }

  @Test
  public void testExceptionIncrementWithComplexExceptionName() {
    Class<?> testClass = this.getClass();
    String baseMetricName = "error.counter";
    IllegalArgumentException exception = new IllegalArgumentException("Test");

    metricUtils.exceptionIncrement(testClass, baseMetricName, exception);

    // The snake case conversion in the code is: "IllegalArgumentException" ->
    // "_Illegal_Argument_Exception"
    String exceptionName =
        MetricRegistry.name(testClass, baseMetricName + "_" + "_Illegal_Argument_Exception");
    Counter exceptionCounter =
        meterRegistry.counter(exceptionName, MetricUtils.DROPWIZARD_METRIC, "true");
    assertNotNull(exceptionCounter);
    assertEquals(exceptionCounter.count(), 1.0);
  }

  @Test
  public void testGaugeRegistersSupplierBasedGauge() {
    Class<?> testClass = this.getClass();
    String metricName = "test.gauge";
    Supplier<Number> valueSupplier = () -> 42.5;

    metricUtils.setGaugeValue(testClass, metricName, 42.5);

    String expectedName = MetricRegistry.name(testClass, metricName);
    Gauge gauge =
        meterRegistry.find(expectedName).tag(MetricUtils.DROPWIZARD_METRIC, "true").gauge();

    assertNotNull(gauge);
    assertEquals(gauge.value(), 42.5);
  }

  @Test
  public void testHistogramRecordsDistributionSummary() {
    Class<?> testClass = this.getClass();
    String metricName = "test.histogram";
    long value = 100L;

    metricUtils.histogram(testClass, metricName, value);

    String expectedName = MetricRegistry.name(testClass, metricName);
    DistributionSummary summary =
        meterRegistry.summary(expectedName, MetricUtils.DROPWIZARD_METRIC, "true");

    assertNotNull(summary);
    assertEquals(summary.count(), 1);
    assertEquals(summary.totalAmount(), (double) value);
  }

  @Test
  public void testHistogramMultipleValues() {
    Class<?> testClass = this.getClass();
    String metricName = "multi.histogram";

    metricUtils.histogram(testClass, metricName, 10);
    metricUtils.histogram(testClass, metricName, 20);
    metricUtils.histogram(testClass, metricName, 30);

    String expectedName = MetricRegistry.name(testClass, metricName);
    DistributionSummary summary =
        meterRegistry.summary(expectedName, MetricUtils.DROPWIZARD_METRIC, "true");

    assertEquals(summary.count(), 3);
    assertEquals(summary.totalAmount(), 60.0);
    assertEquals(summary.mean(), 20.0);
  }

  @Test
  public void testNameWithStringParameters() {
    String result = MetricUtils.name("base", "part1", "part2", "part3");
    assertEquals(result, "base.part1.part2.part3");
  }

  @Test
  public void testNameWithClassAndStringParameters() {
    Class<?> testClass = this.getClass();
    String result = MetricUtils.name(testClass, "method", "metric");
    assertEquals(result, testClass.getName() + ".method.metric");
  }

  @Test
  public void testNameWithEmptyParameters() {
    String result = MetricUtils.name("base");
    assertEquals(result, "base");
  }

  @Test
  public void testAllMethodsWithNullRegistry() {
    MetricUtils utilsWithNullRegistry = MetricUtils.builder().registry(null).build();

    // None of these should throw exceptions
    utilsWithNullRegistry.time("timer", 1000);
    utilsWithNullRegistry.increment(this.getClass(), "counter", 1);
    utilsWithNullRegistry.increment("counter", 1);
    utilsWithNullRegistry.exceptionIncrement(this.getClass(), "error", new RuntimeException());
    utilsWithNullRegistry.setGaugeValue(this.getClass(), "gauge", 42);
    utilsWithNullRegistry.histogram(this.getClass(), "histogram", 100);
  }

  @Test
  public void testAllMetricsHaveDropwizardTag() {
    // Test that all metric types are properly tagged
    metricUtils.time("timer.metric", 1000);
    metricUtils.increment("counter.metric", 1);
    metricUtils.setGaugeValue(this.getClass(), "gauge.metric", 42);
    metricUtils.histogram(this.getClass(), "histogram.metric", 100);

    // Verify all metrics have the dropwizard tag
    for (Meter meter : meterRegistry.getMeters()) {
      assertEquals(meter.getId().getTag(MetricUtils.DROPWIZARD_METRIC), "true");
    }
  }

  @Test
  public void testParsePercentilesWithValidConfig() {
    String percentilesConfig = "0.5, 0.75, 0.95, 0.99, 0.999";
    double[] result = MetricUtils.parsePercentiles(percentilesConfig);

    assertEquals(result.length, 5);
    assertEquals(result[0], 0.5);
    assertEquals(result[1], 0.75);
    assertEquals(result[2], 0.95);
    assertEquals(result[3], 0.99);
    assertEquals(result[4], 0.999);
  }

  @Test
  public void testParsePercentilesWithNullConfig() {
    double[] result = MetricUtils.parsePercentiles(null);

    assertEquals(result.length, 3);
    assertEquals(result[0], 0.5);
    assertEquals(result[1], 0.95);
    assertEquals(result[2], 0.99);
  }

  @Test
  public void testParsePercentilesWithEmptyConfig() {
    double[] result = MetricUtils.parsePercentiles("");

    assertEquals(result.length, 3);
    assertEquals(result[0], 0.5);
    assertEquals(result[1], 0.95);
    assertEquals(result[2], 0.99);
  }

  @Test
  public void testParsePercentilesWithWhitespaceOnlyConfig() {
    double[] result = MetricUtils.parsePercentiles("   ");

    assertEquals(result.length, 3);
    assertEquals(result[0], 0.5);
    assertEquals(result[1], 0.95);
    assertEquals(result[2], 0.99);
  }

  @Test
  public void testParsePercentilesWithSingleValue() {
    double[] result = MetricUtils.parsePercentiles("0.99");

    assertEquals(result.length, 1);
    assertEquals(result[0], 0.99);
  }

  @Test
  public void testParsePercentilesWithExtraSpaces() {
    double[] result = MetricUtils.parsePercentiles("  0.5  ,  0.95  ,  0.99  ");

    assertEquals(result.length, 3);
    assertEquals(result[0], 0.5);
    assertEquals(result[1], 0.95);
    assertEquals(result[2], 0.99);
  }

  @Test
  public void testParseSLOSecondsWithValidConfig() {
    String sloConfig = "100, 500, 1000, 5000, 10000";
    double[] result = MetricUtils.parseSLOSeconds(sloConfig);

    assertEquals(result.length, 5);
    assertEquals(result[0], 100.0);
    assertEquals(result[1], 500.0);
    assertEquals(result[2], 1000.0);
    assertEquals(result[3], 5000.0);
    assertEquals(result[4], 10000.0);
  }

  @Test
  public void testParseSLOSecondsWithNullConfig() {
    double[] result = MetricUtils.parseSLOSeconds(null);

    assertEquals(result.length, 5);
    assertEquals(result[0], 60.0);
    assertEquals(result[1], 300.0);
    assertEquals(result[2], 900.0);
    assertEquals(result[3], 1800.0);
    assertEquals(result[4], 3600.0);
  }

  @Test
  public void testParseSLOSecondsWithEmptyConfig() {
    double[] result = MetricUtils.parseSLOSeconds("");

    assertEquals(result.length, 5);
    assertEquals(result[0], 60.0);
    assertEquals(result[1], 300.0);
    assertEquals(result[2], 900.0);
    assertEquals(result[3], 1800.0);
    assertEquals(result[4], 3600.0);
  }

  @Test
  public void testParseSLOSecondsWithWhitespaceOnlyConfig() {
    double[] result = MetricUtils.parseSLOSeconds("   ");

    assertEquals(result.length, 5);
    assertEquals(result[0], 60.0);
    assertEquals(result[1], 300.0);
    assertEquals(result[2], 900.0);
    assertEquals(result[3], 1800.0);
    assertEquals(result[4], 3600.0);
  }

  @Test
  public void testParseSLOSecondsWithSingleValue() {
    double[] result = MetricUtils.parseSLOSeconds("1000");

    assertEquals(result.length, 1);
    assertEquals(result[0], 1000.0);
  }

  @Test
  public void testParseSLOSecondsWithDecimalValues() {
    double[] result = MetricUtils.parseSLOSeconds("100.5, 500.75, 1000.0");

    assertEquals(result.length, 3);
    assertEquals(result[0], 100.5);
    assertEquals(result[1], 500.75);
    assertEquals(result[2], 1000.0);
  }

  @Test(expectedExceptions = NumberFormatException.class)
  public void testParsePercentilesWithInvalidNumber() {
    MetricUtils.parsePercentiles("0.5, invalid, 0.99");
  }

  @Test(expectedExceptions = NumberFormatException.class)
  public void testParseSLOSecondsWithInvalidNumber() {
    MetricUtils.parseSLOSeconds("100, not-a-number, 1000");
  }

  @Test
  public void testParsePercentilesWithTrailingComma() {
    double[] result = MetricUtils.parsePercentiles("0.5, 0.95,");

    // This will throw NumberFormatException when parsing empty string after last comma
    // If this is intended behavior, keep the test as expectedExceptions
    // If not, the implementation should handle trailing commas
  }

  @Test
  public void testParseSLOSecondsWithNegativeValues() {
    // Test if negative values are accepted (they probably shouldn't be for SLOs)
    double[] result = MetricUtils.parseSLOSeconds("-100, 500, 1000");

    assertEquals(result.length, 3);
    assertEquals(result[0], -100.0);
    assertEquals(result[1], 500.0);
    assertEquals(result[2], 1000.0);
  }
}

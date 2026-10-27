package com.piggymetrics.account.client;

import com.piggymetrics.account.domain.Account;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.test.context.junit4.SpringRunner;

import static org.hamcrest.Matchers.containsString;

/**
 * @author cdov
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "feign.circuitbreaker.enabled=true"
})
public class StatisticsServiceClientFallbackTest {
    @Autowired
    private StatisticsServiceClient statisticsServiceClient;

    @Rule
    public final OutputCaptureRule outputCapture = new OutputCaptureRule();

    @Test
    public void testUpdateStatisticsWithFailFallback(){
        statisticsServiceClient.updateStatistics("test", new Account());

        outputCapture.expect(containsString("Error during update statistics for account: test"));

    }

}


package com.likelion4thon.Meetdle.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(OutputCaptureExtension.class)
class FeatureLogTests {
    @Test
    void redactsTextAndInvalidFieldNames(CapturedOutput output) {
        var fields = new HashMap<String, Object>();
        fields.put("photoUrl", "https://example.com/private-photo.jpg");
        fields.put("accessToken", "sensitive-access-token");
        fields.put("nickname", "private-nickname");
        fields.put("rawBody", Map.of("secret", "provider-body-secret"));
        fields.put("bad\nfield", true);
        fields.put("photoPresent", true);
        fields.put("writtenRows", 1);
        FeatureLog.info(FeatureLog.Event.AUTH_ACCOUNT_SAVED, null, fields);
        assertThat(output.getOut()).contains("photoPresent=true", "writtenRows=1", "photoUrl=REDACTED")
                .doesNotContain("private-photo.jpg", "sensitive-access-token", "private-nickname", "provider-body-secret", "bad\nfield");
    }

    @Test
    void logsOnlyCommittedWorkUsingCapturedRequestIdAndMetrics(CapturedOutput output) {
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource("jdbc:h2:mem:feature-log", "sa", "")));
        UUID rolledBack = UUID.randomUUID();
        UUID committed = UUID.randomUUID();
        String previous = MDC.get("requestId");
        try {
            MDC.put("requestId", "original-request");
            transactions.executeWithoutResult(transaction -> {
                FeatureLog.committed(FeatureLog.Event.VOTE_SUBMITTED, rolledBack, Map.of("submittedCount", 2));
                transaction.setRollbackOnly();
            });
            assertThat(output.getOut()).doesNotContain(rolledBack.toString());
            transactions.executeWithoutResult(transaction -> {
                var fields = new HashMap<String, Object>();
                fields.put("submittedCount", 3);
                FeatureLog.committed(FeatureLog.Event.VOTE_SUBMITTED, committed, fields);
                fields.put("submittedCount", 99);
                MDC.put("requestId", "later-request");
                assertThat(output.getOut()).doesNotContain(committed.toString());
            });
            assertThat(output.getOut()).contains("event=vote_submitted requestId=original-request roomId=" + committed)
                    .contains("submittedCount=3").doesNotContain("submittedCount=99", "requestId=later-request");
        } finally {
            if (previous == null) MDC.remove("requestId");
            else MDC.put("requestId", previous);
        }
    }
}

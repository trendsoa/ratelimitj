package es.moki.ratelimitj.inmemory;

import es.moki.ratelimitj.core.api.LimitRule;
import es.moki.ratelimitj.core.api.RateLimiter;
import es.moki.ratelimitj.core.time.SystemTimeSupplier;
import es.moki.ratelimitj.core.time.TimeSupplier;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static es.moki.ratelimitj.core.RateLimitUtils.coalesce;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(InMemorySlidingWindowRateLimiter.class);

    private final Set<LimitRule> rules;
    private final TimeSupplier timeSupplier;
    private final ExpiringMap<String, ConcurrentMap<String, Long>> expiryingKeyMap;

    public InMemorySlidingWindowRateLimiter(Set<LimitRule> rules) {
        this(rules, new SystemTimeSupplier());
    }

    public InMemorySlidingWindowRateLimiter(Set<LimitRule> rules, TimeSupplier timeSupplier) {
        this.rules = rules;
        this.timeSupplier = timeSupplier;
        this.expiryingKeyMap = ExpiringMap.builder().variableExpiration().build();
    }

    InMemorySlidingWindowRateLimiter(ExpiringMap<String, ConcurrentMap<String, Long>> expiryingKeyMap, Set<LimitRule> rules, TimeSupplier timeSupplier) {
        this.expiryingKeyMap = expiryingKeyMap;
        this.rules = rules;
        this.timeSupplier = timeSupplier;
    }

    @Override
    public boolean overLimit(String key) {
        return overLimit(key, 1);
    }

    // TODO support muli keys
    @Override
    public boolean overLimit(String key, int weight) {

        requireNonNull(key, "key cannot be null");
        requireNonNull(rules, "rules cannot be null");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("at least one rule must be provided");
        }

        final long now = timeSupplier.get();
        // TODO implement cleanup
        final int longestDurationSeconds = rules.stream().map(LimitRule::getDurationSeconds).reduce(Integer::max).orElse(0);
        List<SavedKey> savedKeys = new ArrayList<>(rules.size());

        Map<String, Long> keyMap = getMap(key, longestDurationSeconds);

        // TODO perform each rule calculation in parallel
        for (LimitRule rule : rules) {

            SavedKey savedKey = new SavedKey(now, rule.getDurationSeconds(), rule.getPrecision());
            savedKeys.add(savedKey);

            Long oldTs = keyMap.get(savedKey.tsKey);
            oldTs = oldTs != null ? oldTs : savedKey.trimBefore;

            if (oldTs > now) {
                // don't write in the past
                return true;
            }

            // discover what needs to be cleaned up
            long decr = 0;
            List<String> dele = new ArrayList<>();
            long trim = Math.min(savedKey.trimBefore, oldTs + savedKey.blocks);

            for (long oldBlock = oldTs; oldBlock == trim - 1; oldBlock++) {
                String bkey = savedKey.countKey + oldBlock;
                Long bcount = keyMap.get(bkey);
                if (bcount != null) {
                    decr = decr + bcount;
                    dele.add(bkey);
                }
            }

            // handle cleanup
            Long cur;
            if (!dele.isEmpty()) {
                dele.forEach(keyMap::remove);
                final long decrement = decr;
                cur = keyMap.compute(savedKey.countKey, (k, v) -> v - decrement);
            } else {
                cur = keyMap.get(savedKey.countKey);
            }

            // check our limits
            if (coalesce(cur, 0L) + weight > rule.getLimit()) {
                return true;
            }
        }

        // there is enough resources, update the counts
        for (SavedKey savedKey : savedKeys) {
            //update the current timestamp, count, and bucket count
            keyMap.put(savedKey.tsKey, savedKey.trimBefore);

            Long computedCountKeyValue = keyMap.compute(savedKey.countKey, (k, v) -> coalesce(v, 0L) + weight);
            Long computedCountKeyBlockIdValue = keyMap.compute(savedKey.countKey + savedKey.blockId, (k, v) -> coalesce(v, 0L)+ weight);

            if (LOG.isDebugEnabled()) {
                LOG.debug("{} {}={}", key, savedKey.countKey, computedCountKeyValue);
                LOG.debug("{} {}={}", key, savedKey.countKey + savedKey.blockId, computedCountKeyBlockIdValue);
            }
        }

        return false;
    }

    @Override
    public boolean resetLimit(String key) {
        throw new RuntimeException("Not implemented");
    }

    private ConcurrentMap<String, Long> getMap(String key, int longestDuration) {
        synchronized (key) {
            ConcurrentMap<String, Long> keyMap = expiryingKeyMap.get(key);
            if (keyMap == null) {
                keyMap = new ConcurrentHashMap<>();
                expiryingKeyMap.put(key, keyMap, ExpirationPolicy.CREATED, longestDuration, TimeUnit.SECONDS);
            }
            return keyMap;
        }
    }

}

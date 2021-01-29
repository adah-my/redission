package com.example.redission;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.TimeUnit;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class TestLock
{

    @Autowired
    private RedissonClient redissonClient;

    @Test
    public void test1() throws InterruptedException {
        new Thread(this::testLockOne).start();
//        new Thread(this::testLockTwo).start();

        TimeUnit.SECONDS.sleep(50);
    }

    public void testLockOne(){
        try {
            RLock lock = redissonClient.getLock("muyi_lock");
            log.info("testLockOne尝试加锁...");
            lock.lock();
            log.info("testLockOne加锁成功...");
            log.info("testLockOne业务开始...");
            TimeUnit.SECONDS.sleep(20);
            log.info("testLockOne业务结束...");
            lock.unlock();
            log.info("testLockOne解锁成功...");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void testLockTwo()  {
        try {
            RLock lock = redissonClient.getLock("muyi_lock");
            log.info("testLockTwo尝试加锁...");
            lock.lock();
            log.info("testLockTwo加锁成功...");
            log.info("testLockTwo业务开始...");
            TimeUnit.SECONDS.sleep(20);
            log.info("testLockTwo业务结束...");
            lock.unlock();
            log.info("testLockTwo解锁成功...");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * 通过上面的代码，我们有以下疑问：
     *      lock()方法是原子性的吗？
     *      lock()有设置过期时间吗？是多少？
     *      lock()实现锁续期了吗？
     *      lock()方法怎么实现阻塞的？又怎么被唤醒？
     *
     * 加锁lua脚本解读：
     * --如果不存在锁："muyi_lock"
     * if (reids.call('exists', KEYS[1]) == 0) then
     *      -- 使用hincrby设置锁：hincrby muyi_lock a1b2c3d4:666 1
     *      reids.call('hincrby', KEYS[1], ARGV[2], 1);
     *      -- 设置过期时间.ARGV[1]==internalLockLeaseTime
     *      redis.call('pexpire', KEYS[1], ARGV[1]);
     *      -- 返回null
     *      return nil;
     *      end;
     *
     * -- 如果当前节点已经设置"muyi_lock" （注意，传了ARGV[2]==节点id）
     * if(reids.call('hexists', KEYS[1], ARGV[2])==1) then
     *      -- 就COUNT++，可重入锁
     *      redis.call('hincrby', KEYS[1], ARGV[2], 1);
     *      -- 设置过期时间。ARGV[1]==internalLockLeaseTime
     *      redis.call('pexpire', KEYS[1], ARGV[1]);
     *      -- 返回null
     *      return nil;
     *      end;
     *
     * -- 已经存在锁，且不是当前节点设置的，就返回锁的过期时间ttl
     * return redis.call('pttl', KEYS[1]);
     *
     * Redisson设计的分布式锁是采用hash结构：
     * LOCK_NAME(锁的KEY) + CLIENT_ID(节点ID) + COUNT(重入次数)
     *
     * Redisson如何实现锁续期：
     * 启动一个定时器：Timeout newTimeout(TimerTask task, long delay, TimeUnit unit);
     *      执行规则是：延迟internalLockLeaseTime/3后执行
     *      注意：每一个定时任务只执行一遍，而且是延迟执行
     *      定时任务的目的是：重新执行一遍lua脚本，完成锁续期，会把锁的ttl拨回到30s
     *      也就是说，Redisson的Watchdog定时任务虽然只执行一次，但每次调用都会递归，所以相当于：重复执行.(异步回调线程为守护线程，主线程任务不执行异步线程也会结束)
     *
     * 锁释放有两种情况：
     *      任务结束，主动unLock()删除锁
     *      任务结束，不调用unLock()，但由于守护线程已经结束，不会有后台线程继续给锁续期，过了30秒自动过期
     *
     *
     * 解锁lua脚本解读：
     * -- 参数解释：
     * -- KEYS[1] => "muyi_lock"
     * -- KEYS[2] => getChannelName()
     * -- ARGV[1] => LockPubSub.UNLOCK_MESSAGE
     * -- ARGV[2] => internalLockLeaseTime
     * -- ARGV[3] => getLockName(threadId)
     *
     * -- 锁已经不存在，返回null
     * if (redis.call('hexists', KEYS[1], ARGV[3]) == 0) then
     *      return nil;
     *      end;
     *
     * -- 锁还存在，执行COUNT-- （重入锁的反向操作）
     * local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1);
     *
     * -- COUNT-- 后仍大于0（之前可能重入了多次）
     * if (counter > 0) then
     *      -- 设置过期时间
     *      redis.call('pexpire', KEYS[1], ARGV[2]);
     *      return 0;
     *
     * -- COUNT-- 后小于等于0，删除锁，并向对应的Channel发送消息(NIO),消息类型是LockPubSub.UNLOCK_MESSAGE(锁释放啦，快来抢~)
     * else
     *      reids.call('del', KEYS[1]);
     *      redis.call('publish', KEYS[2], ARGV[1]);
     *      return 1;
     *      end;
     *
     * return nil;
     *
     *
     * Redisson分布式锁的缺陷
     * 在哨兵模式或者主从模式下，如果master实例宕机，可能会导致多个节点同时完成加锁。
     * 以主从模式为例，由于所有的写操作都是现在master上进行，然后在同步给各个slave节点，所以master与各个slave节点之间的数据具有一定的延迟性。
     * 对于Redisson分布式锁而言，比如客户端刚对master写入Redisson锁，然后master异步复制给各个slave节点，但这个过程中master节点宕机了。
     * 其中一个节点经过选举变成了master节点，好巧不巧，这个slave还没同步到Redisson锁，所起其他客户端可能再次加锁
     * Redisson分布式锁的缺陷
     * 在哨兵模式或者主从模式下，如果master实例宕机，可能导致多个节点同时完成加锁。
     * 以主从模式为例，由于所有的写操作都是先在master上进行，然后再同步给各个slave节点，所以master与各个slave节点之间的数据具有一定的延迟性。
     * 对于Redisson分布式锁而言，比如客户端刚对master写入Redisson锁，然后master异步复制给各个slave节点，但这个过程中master节点宕机了，
     * 其中一个slave节点经过选举变成了master节点，好巧不巧，这个slave还没同步到Reddison锁，所以其他客户端可能再次加锁。
     *
     *
     *
     *
     *
     *
     *
     *
     *
     */































}
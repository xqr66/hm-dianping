package com.hmdp.utils;

public interface ILock {
    /**
     * @description: 尝试获取锁
     * @param: timeoutSec 锁持有的超时时间，过期后自动释放
     * @return: boolean true 代表获取锁成功； false 代表获取锁失败
     * @date: 2023/8/20 17:10
     */

  boolean tryLock(long timeoutSec);


  void unlock();
}

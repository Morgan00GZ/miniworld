package com.miniworld.pocket;

/**
 * 「倒置熄屏」用到的提权侧接口。
 *
 * 实现跑在 Shizuku 的 UserService 进程里（shell, uid=2000），
 * 只有这个身份才能持有接近传感器关屏锁（见 PocketService 的注释）。
 */
interface IPocketService {
    /** 取得「接近传感器关屏」锁；已持有则直接返回 true。 */
    boolean acquire();

    /** 释放锁；没持有也算成功（幂等）。 */
    boolean release();

    /** 当前是否真的持有这把锁。 */
    boolean isHolding();

    /** 服务进程的 uid，用来确认跑在 shell（2000）而不是应用自身。 */
    int peerUid();

    /**
     * 放锁并让服务进程自己退场（用户点「关闭」时用）。
     *
     * 只 release 不退出的话，进程会以 shell 身份一直挂在 ps 里占着内存 —— 真机测过，
     * Shizuku 不会来收它。
     */
    void shutdown();

    /** 最近一次 acquire 用了哪个反射签名、结果如何；只给诊断日志用。 */
    String diagnose();

    /**
     * 让服务盯着宿主进程的 binder：宿主一死（崩溃 / 用户强停）立刻放锁。
     *
     * 没有这一步，锁会留在 shell 进程里没人管 —— 测试时亲眼见到：应用被强停后
     * dumpsys 里那把 PROXIMITY 锁还在 ACQ，手机一进兜就「屏幕灭但整机全速跑」。
     */
    void watchDeath(IBinder client);
}

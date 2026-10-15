package com.rotatelock;

/**
 * 跑在 shell 身份进程里的用户服务（Shizuku UserService）。
 * 16777114 是 Shizuku 服务器保留的 destroy 事务号，必须在解绑时用来结束进程。
 */
interface IUserService {

    void destroy() = 16777114;

    /** 在 shell 身份下执行一条命令，返回 "exit=<退出码>\n<输出>"。 */
    String runCommand(String command) = 1;
}

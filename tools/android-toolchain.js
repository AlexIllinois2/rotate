#!/usr/bin/env node
/**
 * AppRotate 工具链安装 + 构建（Windows / 纯 Node fetch，避开本机 schannel TLS 问题）
 *
 *   node work\android-toolchain.js            # 只装工具链
 *   node work\android-toolchain.js --build    # 装完直接构建 debug APK
 *
 * 安装位置（默认，可用 APPROTATE_ANDROID_ROOT 覆盖）：
 *   D:\dev\android\sdk                              Android SDK
 *   D:\dev\android\gradle\gradle-8.11.1             Gradle 发行版
 *   D:\dev\android\gradle-home                      GRADLE_USER_HOME（依赖缓存）
 *   D:\dev\android\android-home                     ANDROID_USER_HOME（许可 + debug keystore）
 */
const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const WS = path.resolve(__dirname, '..');   // 项目根（tools 的上一级）
const ANDROID_ROOT = process.env.APPROTATE_ANDROID_ROOT || 'D:\\dev\\android';
const SDK = path.join(ANDROID_ROOT, 'sdk');
const GRADLE_ROOT = path.join(ANDROID_ROOT, 'gradle');
const GRADLE_VERSION = '8.11.1';
const GRADLE_HOME = path.join(GRADLE_ROOT, 'gradle-' + GRADLE_VERSION);
const GRADLE_USER_HOME = path.join(ANDROID_ROOT, 'gradle-home');
const ANDROID_USER_HOME = path.join(ANDROID_ROOT, 'android-home');
const DOWNLOADS = process.env.APPROTATE_DOWNLOADS || path.join('D:\\dev\\android', 'cache');
const LOG_FILE = path.join(WS, 'dist', 'toolchain.log');
const JAVA_HOME = process.env.APPROTATE_JAVA_HOME || 'D:\\dev\\lang\\java\\jdk-21.0.12';
const PLATFORM = 'platforms;android-36';
const BUILD_TOOLS = 'build-tools;36.0.0';
const GRADLE_URLS = [
  'https://mirrors.huaweicloud.com/gradle/gradle-' + GRADLE_VERSION + '-bin.zip',
  'https://mirrors.cloud.tencent.com/gradle/gradle-' + GRADLE_VERSION + '-bin.zip',
  'https://services.gradle.org/distributions/gradle-' + GRADLE_VERSION + '-bin.zip',
];
const PROJECT = WS;
const APK_OUT = path.join(WS, 'dist', 'AppRotate-debug.apk');

fs.mkdirSync(path.dirname(LOG_FILE), { recursive: true });
function log(m) {
  const line = m + '\n';
  process.stdout.write(line);
  try { fs.appendFileSync(LOG_FILE, line); } catch (e) {}
}

function buildEnv() {
  return Object.assign({}, process.env, {
    JAVA_HOME,
    ANDROID_HOME: SDK,
    ANDROID_SDK_ROOT: SDK,
    ANDROID_USER_HOME,
    GRADLE_USER_HOME,
    PATH: [
      path.join(SDK, 'platform-tools'),
      path.join(SDK, 'cmdline-tools', 'latest', 'bin'),
      path.join(GRADLE_HOME, 'bin'),
      path.join(JAVA_HOME, 'bin'),
      process.env.PATH || '',
    ].join(';'),
  });
}

function run(file, args, extra) {
  log('> ' + path.basename(file) + ' ' + args.join(' '));
  const r = cp.spawnSync(file, args, Object.assign({ stdio: 'inherit', env: buildEnv() }, extra || {}));
  if (r.status !== 0) throw new Error('命令失败(' + r.status + '): ' + path.basename(file) + ' ' + args.join(' '));
}

function runCmd(args, extra) {
  return run('cmd.exe', ['/c'].concat(args), extra);
}

/** 跨盘 rename 会报 EXDEV，失败时退化为复制 + 删除。 */
function moveDir(from, to) {
  try {
    fs.renameSync(from, to);
  } catch (e) {
    fs.cpSync(from, to, { recursive: true });
    fs.rmSync(from, { recursive: true, force: true });
  }
}

async function download(url, dest) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  for (let attempt = 1; attempt <= 8; attempt++) {
    const cached = fs.existsSync(dest) ? fs.statSync(dest).size : 0;
    let offset = cached;
    try {
      log('下载 ' + path.basename(dest) + '（本地已有 ' + (offset / 1048576).toFixed(1) + ' MB）');
      const headers = offset > 0 ? { Range: 'bytes=' + offset + '-' } : {};
      const res = await fetch(url, { headers, redirect: 'follow' });
      if (res.status === 416) {
        // 本地文件已经完整（或 Range 非法）：确认后直接用，否则丢弃重下
        const total = Number(((res.headers.get('content-range') || '').split('/')[1] || '0').trim());
        if (total > 0 && cached === total) {
          log('  本地缓存已完整（' + (cached / 1048576).toFixed(1) + ' MB），跳过下载');
          return dest;
        }
        fs.rmSync(dest, { force: true });
        throw new Error('HTTP 416 (offset=' + cached + ' total=' + total + ')，已丢弃本地缓存');
      }
      if (res.status !== 200 && res.status !== 206) throw new Error('HTTP ' + res.status);
      if (res.status === 200 && offset > 0) offset = 0;   // 服务端忽略了 Range，从头写
      const total = offset + Number(res.headers.get('content-length') || 0);
      const sink = fs.createWriteStream(dest, { flags: offset > 0 ? 'a' : 'w' });
      const reader = res.body.getReader();
      let done = offset;
      let mark = done + 25 * 1024 * 1024;
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        if (!sink.write(Buffer.from(chunk.value))) await new Promise((r) => sink.once('drain', r));
        done += chunk.value.length;
        if (done >= mark) { mark = done + 25 * 1024 * 1024; log('  ' + (done / 1048576).toFixed(1) + '/' + (total / 1048576).toFixed(1) + ' MB'); }
      }
      await new Promise((ok, bad) => sink.end((e) => (e ? bad(e) : ok())));
      if (total && done < total) throw new Error('长度不足 ' + done + '/' + total);
      log('  完成 ' + (done / 1048576).toFixed(1) + ' MB');
      return dest;
    } catch (e) {
      log('  第 ' + attempt + ' 次失败: ' + e.message);
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  throw new Error('下载失败: ' + url);
}

async function cmdlineToolsUrl() {
  const res = await fetch('https://dl.google.com/android/repository/repository2-3.xml');
  const text = await res.text();
  const names = [...new Set([...text.matchAll(/commandlinetools-win-[0-9]+_latest\.zip/g)].map((m) => m[0]))];
  if (!names.length) throw new Error('解析 cmdline-tools 版本失败');
  names.sort((a, b) => Number(b.match(/win-([0-9]+)/)[1]) - Number(a.match(/win-([0-9]+)/)[1]));
  return { name: names[0], url: 'https://dl.google.com/android/repository/' + names[0] };
}

async function installSdk() {
  const sdkManager = path.join(SDK, 'cmdline-tools', 'latest', 'bin', 'sdkmanager.bat');
  if (!fs.existsSync(sdkManager)) {
    const tools = await cmdlineToolsUrl();
    log('cmdline-tools 版本: ' + tools.name);
    const zip = await download(tools.url, path.join(DOWNLOADS, tools.name));
    // 解压到与目标同卷的临时目录再改名，避免跨盘 rename
    const tmp = path.join(ANDROID_ROOT, '.tmp-cmdline-tools');
    fs.rmSync(tmp, { recursive: true, force: true });
    fs.mkdirSync(tmp, { recursive: true });
    run('tar', ['-xf', zip, '-C', tmp]);
    const latest = path.join(SDK, 'cmdline-tools', 'latest');
    fs.rmSync(latest, { recursive: true, force: true });
    fs.mkdirSync(path.dirname(latest), { recursive: true });
    moveDir(path.join(tmp, 'cmdline-tools'), latest);
    fs.rmSync(tmp, { recursive: true, force: true });
  } else {
    log('cmdline-tools 已就绪');
  }

  const hasPlatform = fs.existsSync(path.join(SDK, 'platforms', 'android-36', 'android.jar'));
  const hasBuildTools = fs.existsSync(path.join(SDK, 'build-tools', '36.0.0', 'aapt2.exe'));
  const hasAdb = fs.existsSync(path.join(SDK, 'platform-tools', 'adb.exe'));
  if (hasPlatform && hasBuildTools && hasAdb) {
    log('SDK 组件齐全');
    return;
  }
  // 包名里的 `;` 会被 cmd 当分隔符吃掉，所以写成批处理并把包名引起来
  log('安装 platform-tools / ' + PLATFORM + ' / ' + BUILD_TOOLS + ' ...');
  const wrapper = path.join(WS, 'work', 'sdk-install.cmd');
  fs.writeFileSync(
    wrapper,
    '@echo off\r\n' +
      'set "JAVA_HOME=' + JAVA_HOME + '"\r\n' +
      'set "ANDROID_HOME=' + SDK + '"\r\n' +
      'set "ANDROID_SDK_ROOT=' + SDK + '"\r\n' +
      'set "ANDROID_USER_HOME=' + ANDROID_USER_HOME + '"\r\n' +
      'call "' + sdkManager + '" --sdk_root="' + SDK + '" --install "platform-tools" "' + PLATFORM + '" "' + BUILD_TOOLS + '"\r\n',
    'utf8',
  );
  runCmd([wrapper]);
}

async function installGradle() {
  if (fs.existsSync(path.join(GRADLE_HOME, 'bin', 'gradle.bat'))) {
    log('Gradle 已就绪');
    return;
  }
  const name = 'gradle-' + GRADLE_VERSION + '-bin.zip';
  const zip = await download('https://services.gradle.org/distributions/' + name, path.join(DOWNLOADS, name));
  fs.mkdirSync(GRADLE_ROOT, { recursive: true });
  run('tar', ['-xf', zip, '-C', GRADLE_ROOT]);
}

function build() {
  fs.mkdirSync(ANDROID_USER_HOME, { recursive: true });
  fs.mkdirSync(GRADLE_USER_HOME, { recursive: true });
  runCmd([path.join(GRADLE_HOME, 'bin', 'gradle.bat'), '--no-daemon', '-p', PROJECT, 'assembleDebug']);
  const apk = path.join(PROJECT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
  if (!fs.existsSync(apk)) throw new Error('未生成 APK: ' + apk);
  fs.mkdirSync(path.dirname(APK_OUT), { recursive: true });
  fs.copyFileSync(apk, APK_OUT);
  log('APK -> ' + APK_OUT + ' (' + (fs.statSync(APK_OUT).size / 1048576).toFixed(2) + ' MB)');
}

(async () => {
  log('==== ' + new Date().toISOString() + ' 开始 ====');
  log('工作区: ' + WS);
  log('ANDROID_ROOT: ' + ANDROID_ROOT);
  fs.mkdirSync(ANDROID_ROOT, { recursive: true });
  await installSdk();
  await installGradle();
  if (process.argv.includes('--build')) build();
  log('==== TOOLCHAIN_OK ====');
})().catch((e) => { log('==== FAILED: ' + e.message + ' ===='); process.exit(1); });

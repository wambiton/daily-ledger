# 个人记账（Android）

需求见 [PRD](PRD.md)，执行状态见 [开发进度](开发进度.md)。当前代码处于分阶段开发，不是完整产品。

## 本地运行

Windows 项目根目录运行：

```powershell
python scripts/bootstrap.py
./scripts/android.ps1
```

构建脚本当前使用本机 `E:\jdk-17`，其他机器需要将其改为本机 JDK 17 路径。Android SDK 和 Gradle 都放在项目 `.tools`；网络需要代理时在 `.tools/gradle-user/gradle.properties` 设置，不把个人代理配置提交到源码。Windows 中文路径通过 `R:` 别名兼容，不移动项目；若该盘符已被其他目录使用，脚本会停止而不会覆盖。结束所有构建和模拟器进程后可用 `subst R: /D` 移除别名。

APK 输出：`android/app/build/outputs/apk/debug/app-debug.apk`。仅在构建成功后生成。

服务端：

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r server/requirements.txt
./.venv/Scripts/python.exe server/manage.py migrate
./.venv/Scripts/python.exe server/manage.py test ledger
./.venv/Scripts/python.exe scripts/smoke_server.py
```

开发测试默认 SQLite。正式环境需配置 PostgreSQL 与独立密钥，不能使用开发配置对外提供服务。

## 阶段检查

- P0：`python scripts/check_design.py`，输出静态页面截图及检查结果。
- P1：安卓编译、单元测试、Room 持久化检查；服务端模型约束和 HTTP 启动检查。
- 实际已执行结果以 `reports` 和《开发进度》为准；未通过的检查不标为完成。

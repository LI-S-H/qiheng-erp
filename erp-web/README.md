# 启衡 ERP 前端

## VSCode 启动方式

1. 用 VSCode 打开项目根目录

```text
E:\Projects\ERP
```

2. 打开 VSCode 终端

菜单路径：

```text
Terminal -> New Terminal
```

3. 进入前端目录

```powershell
cd E:\Projects\ERP\erp-web
```

4. 安装依赖

第一次启动需要执行：

```powershell
npm.cmd install
```

5. 启动开发服务

```powershell
npm.cmd run dev -- --host 0.0.0.0 --port 5173
```

6. 浏览器访问

```text
http://localhost:5173/
```

## 开发环境登录

当前后端登录接口尚未生成，前端开发环境启用了 mock 登录。

登录账号：

```text
admin
```

登录密码：

```text
123456
```

登录页会默认填入该账号密码。

后端登录接口完成后，把 `.env.development` 改为：

```text
VITE_USE_MOCK_AUTH=false
```

即可切换为真实接口登录。

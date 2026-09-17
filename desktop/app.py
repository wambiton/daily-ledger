"""Windows companion. Only the user-started process owns its server and tunnel."""
import argparse
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serve', action='store_true')
    parser.add_argument('--data-dir')
    parser.add_argument('--port', type=int, default=18765)
    parser.add_argument('--smoke-test', action='store_true')
    parser.add_argument('--diagnostic-file')
    args = parser.parse_args()
    from core import resolve_data_folder, remember_data_folder
    args.data_dir = str(resolve_data_folder(args.data_dir, sys.executable if getattr(sys,'frozen',False) else __file__))
    if args.serve:
        from origin import run
        run(args.data_dir, args.port)
        return
    import tkinter as tk
    from tkinter import ttk, messagebox
    import msvcrt
    from PIL import Image, ImageTk, ImageDraw
    import qrcode
    import pystray
    from core import identity, mail_config, store_mail, pairing, summaries, saved_mail_status, verify_mail
    folder = Path(args.data_dir).resolve(); folder.mkdir(parents=True, exist_ok=True)
    lock = (folder/'desktop.lock').open('a+b'); lock.seek(0); lock.write(b'0'); lock.flush(); lock.seek(0)
    try: msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        messagebox.showinfo('个人记账电脑端', '电脑端程序已运行，请从系统托盘打开。'); return
    computer = identity(folder)
    if not args.smoke_test and (folder/'accounts.sqlite3').exists():
        remember_data_folder(folder)
    root = tk.Tk(); root.title('个人记账 · 电脑备份'); root.geometry('850x770'); root.minsize(760,660)
    events = queue.Queue(); processes = []; stopped = threading.Event(); closing = False; started = False
    style = ttk.Style(); style.theme_use('clam'); style.configure('.', font=('Microsoft YaHei UI', 10))
    panel = ttk.Frame(root, padding=22); panel.pack(fill='both', expand=True)
    ttk.Label(panel, text='自己的账目，保存在自己的电脑', font=('Microsoft YaHei UI',16,'bold')).pack(anchor='w')
    ttk.Label(panel,text=f'当前账号数据目录：{folder}',wraplength=780).pack(anchor='w',pady=4)
    ttk.Label(panel,text='SMTP授权码仅用于发邮件，不是手机登录密码；账号保存在上述目录，不会定期过期。',wraplength=780).pack(anchor='w')
    status = tk.StringVar(value='连接未启动；保存发信设置会自动启动连接服务。'); ttk.Label(panel,textvariable=status,wraplength=780).pack(anchor='w',pady=10)
    setup = ttk.LabelFrame(panel,text='QQ邮箱发信设置',padding=12); setup.pack(fill='x')
    current_email, _ = mail_config(folder)
    email = tk.StringVar(value=current_email); password = tk.StringVar()
    ttk.Label(setup,text='QQ邮箱').grid(row=0,column=0,padx=5,pady=4)
    ttk.Entry(setup,textvariable=email,width=28).grid(row=0,column=1,padx=5)
    ttk.Label(setup,text='SMTP授权码').grid(row=0,column=2,padx=5)
    ttk.Entry(setup,textvariable=password,show='●',width=22).grid(row=0,column=3,padx=5)
    mail_status = tk.StringVar(value=saved_mail_status(folder))
    ttk.Label(setup,textvariable=mail_status,wraplength=710).grid(row=2,column=0,columnspan=4,sticky='w',pady=5)
    mail_revision = 0
    def save_mail():
        nonlocal mail_revision
        try:
            saved_email, saved_password = mail_config(folder)
            if password.get().strip():
                store_mail(folder,email.get(),password.get())
            elif email.get().strip().lower() != saved_email or not saved_password:
                raise ValueError('missing credentials')
            elif started:
                mail_status.set(saved_mail_status(folder));return
        except Exception:
            mail_status.set('保存失败；请检查邮箱及授权码，原设置未清空。');return
        mail_revision += 1
        password.set('')
        mail_status.set(saved_mail_status(folder))
        if started:stop()
        start()
    def check_mail():
        revision = mail_revision
        mail_status.set('正在验证已保存的发信配置…')
        def validate():
            result=verify_mail(folder)
            events.put(('mail',revision,result))
        threading.Thread(target=validate,daemon=True).start()
    ttk.Button(setup,text='保存设置',command=save_mail).grid(row=1,column=3,sticky='e',pady=5)
    ttk.Button(setup,text='验证发信配置',command=check_mail).grid(row=1,column=1,sticky='w',pady=5)
    body = ttk.Frame(panel); body.pack(fill='x',pady=10)
    qr_label = ttk.Label(body,text='启动成功后在这里显示配对二维码',width=40,anchor='center'); qr_label.pack(side='left')
    tips = ttk.Frame(body,padding=12);tips.pack(side='left',fill='both',expand=True)
    ttk.Label(tips,text='手机 → 我的 → 扫码连接电脑\n\n首次注册自动带入本机账目。\n换手机扫码并登录即可找回已备份账目。\n\n免费临时地址可能变化，请重新扫码。\n免费连接不保证持续可用。\n二维码仅提供给信任的使用者。',wraplength=380).pack(anchor='w')
    def stop():
        nonlocal started
        stopped.set()
        for process in list(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=4)
                except subprocess.TimeoutExpired: process.kill()
        processes.clear();started=False
        qr_label.configure(image='',text='已停止，启动后生成新的配对二维码');qr_label.image=None
        status.set('备份服务已停止，手机账目仍保留。')
    def spawn(command, token):
        if token.is_set(): raise RuntimeError('stopped')
        child=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        processes.append(child)
        if token.is_set():child.terminate();raise RuntimeError('stopped')
        return child
    def connect(token):
        try:
            if getattr(sys,'frozen',False): command=[sys.executable]
            else: command=[sys.executable,str(Path(__file__).resolve())]
            server = spawn(command+['--serve','--data-dir',str(folder),'--port',str(args.port)],token)
            # Drain without logging tokens or personal data.
            def drain():
                for _ in iter(server.stdout.readline,b''): pass
            threading.Thread(target=drain,daemon=True).start()
            request=urllib.request.Request(f'http://127.0.0.1:{args.port}/api/v1/computer/',headers={'X-Ledger-Pair':computer['pair_key']})
            for _ in range(40):
                if token.wait(.5):return
                if server.poll() is not None:raise RuntimeError('server')
                try:
                    with urllib.request.urlopen(request,timeout=2) as r:
                        if json.load(r)['computer_id']==computer['computer_id']:break
                except Exception:continue
            else:raise RuntimeError('server')
            resources=Path(getattr(sys,'_MEIPASS',Path(__file__).resolve().parent.parent))
            executable=resources/'cloudflared.exe' if getattr(sys,'frozen',False) else resources/'.tools/cloudflared.exe'
            tunnel=spawn([str(executable),'tunnel','--no-autoupdate','--protocol','http2','--http-host-header','localhost','--url',f'http://127.0.0.1:{args.port}'],token)
            url=None; registered=False
            for raw in iter(tunnel.stdout.readline,b''):
                if token.is_set():return
                line=raw.decode(errors='replace')
                match=re.search(r'https://[a-z0-9-]+\.trycloudflare\.com',line)
                if match:url=match.group()
                if 'Registered tunnel connection' in line:registered=True
                if url and registered:
                    events.put((token,'ready',url));url=None
            if not token.is_set():events.put((token,'error','外网连接已断开，请停止后重新启动并重新扫码。'))
        except Exception:
            if not token.is_set():events.put((token,'error','连接未成功。请检查网络、端口是否被占用，然后停止并重试。'))
    def start():
        nonlocal started, stopped
        if started:return
        if password.get():
            save_mail();return
        if not mail_config(folder)[0] and not messagebox.askyesno('尚未配置发信邮箱','可先测试连接，但不能发送注册验证码。是否继续？'):return
        stopped=threading.Event();started=True;status.set('正在启动电脑服务并建立外网连接，请等待二维码…')
        token=stopped
        threading.Thread(target=connect,args=(token,),daemon=True).start()
        def connection_timeout():
            if token is stopped and not token.is_set() and not getattr(qr_label,'image',None):
                stop();status.set('连接失败：外网通道建立超时。授权码已保留，请检查电脑网络后重新启动连接。')
        root.after(90000,connection_timeout)
    buttons=ttk.Frame(panel);buttons.pack(fill='x',pady=5)
    ttk.Button(buttons,text='启动外网备份',command=start).pack(side='left',padx=4)
    ttk.Button(buttons,text='停止服务',command=stop).pack(side='left',padx=4)
    ttk.Button(buttons,text='打开数据文件夹',command=lambda:os.startfile(folder)).pack(side='left',padx=4)
    def choose_data():
        from tkinter import filedialog
        selected = filedialog.askdirectory(title='选择原账号数据目录（包含accounts.sqlite3）',initialdir=folder)
        if not selected:return
        target=Path(selected).resolve()
        if target == folder:return
        if not (target/'accounts.sqlite3').is_file():
            messagebox.showerror('无法切换','该目录没有账号数据库，请选择原备份程序的数据目录。');return
        if not messagebox.askyesno('切换账号数据目录','将停止当前服务并打开所选目录。两处原始数据均保留，不会合并或覆盖。是否继续？'):return
        remember_data_folder(target)
        command=[sys.executable] if getattr(sys,'frozen',False) else [sys.executable,str(Path(__file__).resolve())]
        quit_app()
        subprocess.Popen(command+['--data-dir',str(target)],creationflags=subprocess.CREATE_NO_WINDOW)
    ttk.Button(buttons,text='选择原账号数据目录',command=choose_data).pack(side='left',padx=4)
    ttk.Label(panel,text='此电脑的账号备份',font=('Microsoft YaHei UI',12,'bold')).pack(anchor='w',pady=(12,5))
    table=ttk.Treeview(panel,columns=('email','time','count','result'),show='headings',height=6)
    for key,title,width in [('email','QQ邮箱',220),('time','最近成功备份时间',230),('count','账目笔数',90),('result','结果',100)]:
        table.heading(key,text=title);table.column(key,width=width)
    table.pack(fill='both',expand=True)
    def refresh():
        if closing:return
        try:
            rows=summaries(folder);table.delete(*table.get_children())
            for address,when,revision,payload in rows:
                count=len(json.loads(payload)['entries']) if payload else 0
                table.insert('', 'end', values=(address,when or '尚未备份',count,f'成功 · v{revision}' if revision else '尚未备份'))
        except Exception:pass
        root.after(4000,refresh)
    icon_image=Image.new('RGB',(64,64),'#3465d9');drawing=ImageDraw.Draw(icon_image);drawing.rectangle((18,12,46,52),outline='white',width=4)
    def show():root.after(0,lambda:(root.deiconify(),root.lift()))
    def quit_app():
        nonlocal closing
        if closing:return
        closing=True;stop();tray.stop();root.destroy();lock.close()
    tray=pystray.Icon('PersonalLedgerBackup',icon_image,'个人记账电脑备份',menu=pystray.Menu(
        pystray.MenuItem('打开窗口',lambda:show(),default=True),pystray.MenuItem('退出程序',lambda:root.after(0,quit_app))))
    threading.Thread(target=tray.run,daemon=True).start()
    root.protocol('WM_DELETE_WINDOW',root.withdraw)
    def pump():
        if closing:return
        while not events.empty():
            token,kind,value=events.get()
            if token == 'mail':
                if kind == mail_revision:mail_status.set(value)
                continue
            if token is not stopped or token.is_set():continue
            if kind=='ready':
                picture=qrcode.make(pairing(value,computer)).resize((290,290),Image.Resampling.NEAREST)
                photo=ImageTk.PhotoImage(picture);qr_label.configure(image=photo,text='');qr_label.image=photo
                status.set('外网通道已连接，请用手机扫码。关闭窗口后仍在系统托盘运行。')
            else:
                stop();status.set(value)
        root.after(200,pump)
    refresh();pump()
    if args.smoke_test:root.after(1500,quit_app)
    try:root.mainloop()
    finally:lock.close()

if __name__=='__main__':
    try:
        main()
    except Exception:
        if '--diagnostic-file' in sys.argv:
            import traceback
            Path(sys.argv[sys.argv.index('--diagnostic-file')+1]).write_text(traceback.format_exc(),encoding='utf-8')
        else:
            from tkinter import messagebox
            messagebox.showerror('电脑备份启动失败','程序未能启动，请检查数据目录的访问权限或联系开发者。')
        raise SystemExit(1)

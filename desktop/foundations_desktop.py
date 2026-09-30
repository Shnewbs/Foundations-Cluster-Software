"""Optional, dependency-free desktop client. The Manager remains authoritative."""
import concurrent.futures, http.cookiejar, json, queue, threading, tkinter as tk
from tkinter import ttk, messagebox
import urllib.request, urllib.error, webbrowser

class ControlCenter:
    def __init__(self):
        self.root=tk.Tk();self.root.title('Foundations Cluster — Control Center');self.root.geometry('1080x720');self.root.configure(bg='#101d26')
        style=ttk.Style();style.theme_use('clam');style.configure('.',background='#152630',foreground='#dceaf2',fieldbackground='#0a1820');style.configure('Treeview',rowheight=29);style.map('Treeview',background=[('selected','#176b78')]);style.configure('TButton',padding=7)
        self.pool=concurrent.futures.ThreadPoolExecutor(max_workers=2);self.results=queue.Queue();self.lock=threading.Lock();self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.connected=False;self.busy=False;self.closed=False
        self.url=tk.StringVar(value='http://127.0.0.1:8765');self.user=tk.StringVar(value='admin');self.password=tk.StringVar();self.status=tk.StringVar(value='Sign in with your existing Manager account. First-run setup is in the WebUI.')
        bar=ttk.Frame(self.root,padding=14);bar.pack(fill='x');ttk.Label(bar,text='FOUNDATIONS',font=('Segoe UI',18,'bold')).pack(side='left');ttk.Button(bar,text='Open WebUI',command=lambda:webbrowser.open(self.url.get())).pack(side='right')
        login=ttk.Frame(self.root,padding=12);login.pack(fill='x')
        for title,var,secret in [('Manager URL',self.url,False),('Username',self.user,False),('Password',self.password,True)]:
            ttk.Label(login,text=title).pack(side='left',padx=5);ttk.Entry(login,textvariable=var,width=27 if title=='Manager URL' else 18,show='•' if secret else '').pack(side='left')
        ttk.Button(login,text='Sign in',command=self.login).pack(side='left',padx=9)
        self.summary=ttk.Label(self.root,text='Waiting for Manager',font=('Segoe UI',15),padding=16);self.summary.pack(fill='x')
        tabs=ttk.Notebook(self.root);tabs.pack(fill='both',expand=True,padx=14,pady=8)
        page=ttk.Frame(tabs);tabs.add(page,text='Workers');logpage=ttk.Frame(tabs);tabs.add(logpage,text='Manager Log')
        fields=('id','state','players','mspt','memory','pinned');self.table=ttk.Treeview(page,columns=fields,show='headings');self.table.pack(fill='both',expand=True)
        for f in fields:self.table.heading(f,text=f.upper());self.table.column(f,width=120,anchor='w')
        controls=ttk.Frame(page,padding=10);controls.pack(fill='x')
        for label,action in [('Start Worker','spawn'),('Drain','drain'),('Cancel Drain','undrain'),('Graceful Stop','stop'),('Restart','restart'),('Pin','pin'),('Unpin','unpin')]:ttk.Button(controls,text=label,command=lambda a=action:self.action(a)).pack(side='left',padx=3)
        self.log=tk.Text(logpage,bg='#061018',fg='#b7d0dd',font=('Consolas',10),state='disabled',wrap='none');self.log.pack(fill='both',expand=True)
        ttk.Label(self.root,textvariable=self.status,padding=12,wraplength=1000).pack(fill='x')
        self.root.protocol('WM_DELETE_WINDOW',self.close);self.root.after(100,self.poll);self.root.after(3000,self.refresh_timer)
    def api(self,path,data=None):
        request=urllib.request.Request(self.base+path,None if data is None else json.dumps(data).encode(),{'Content-Type':'application/json'})
        with self.lock:
            try:
                with self.opener.open(request,timeout=8) as response:return json.load(response)
            except urllib.error.HTTPError as e:
                try:detail=json.load(e).get('error',str(e))
                except Exception:detail=str(e)
                raise RuntimeError(detail) from e
    def submit(self,work,done):
        future=self.pool.submit(work)
        def finished(f):
            try:self.results.put((done,f.result(),None))
            except Exception as e:self.results.put((done,None,str(e)))
        future.add_done_callback(finished)
    def poll(self):
        if self.closed:return
        while True:
            try:done,value,error=self.results.get_nowait()
            except queue.Empty:break
            if error:self.status.set('Request failed: '+error);self.busy=False
            else:done(value)
        self.root.after(100,self.poll)
    def login(self):
        self.base=self.url.get().strip().rstrip('/')
        if not self.base.startswith(('http://','https://')):self.status.set('Enter a valid http:// or https:// Manager URL');return
        body={'username':self.user.get(),'password':self.password.get(),'remember':False};self.password.set('')
        self.submit(lambda:self.api('/api/auth/login',body),self.logged_in)
    def logged_in(self,_):self.connected=True;self.status.set('Connected. Full Test Lab, regions, layers and settings are available in Open WebUI.');self.refresh()
    def refresh(self):
        if not self.connected or self.busy:return
        self.busy=True;self.submit(lambda:(self.api('/api/status'),self.api('/api/workers'),self.api('/api/logs?limit=250')),self.render)
    def render(self,data):
        self.busy=False;s,w,logs=data;self.summary.configure(text=f"{s['version']}   •   {s['readyWorkers']}/{s['workers']} workers   •   {s['players']} players   •   {s['avgMspt']:.1f} ms   •   {s.get('lifecycle','RUNNING')}")
        selected=self.table.selection();self.table.delete(*self.table.get_children())
        for worker in w['workers']:
            self.table.insert('', 'end',iid=worker['id'],values=(worker['id'],worker['state'],worker['players'],round(worker['mspt'],1),f"{worker['heapUsed']/2**30:.1f} / {worker['heapMax']/2**30:.1f} GB",'Yes' if worker.get('pinned') else ''))
        for item in selected:
            if self.table.exists(item):self.table.selection_add(item)
        self.log.configure(state='normal');self.log.delete('1.0','end');self.log.insert('end','\n'.join(logs['lines']));self.log.see('end');self.log.configure(state='disabled')
    def refresh_timer(self):
        if self.closed:return
        self.refresh();self.root.after(3000,self.refresh_timer)
    def action(self,action):
        if not self.connected:self.status.set('Sign in first');return
        if action=='spawn':path='/api/workers/spawn'
        else:
            selected=self.table.selection()
            if not selected:self.status.set('Select a worker first');return
            worker=selected[0]
            if action in ('stop','restart') and not messagebox.askyesno('Confirm',f'{action.title()} {worker} after graceful evacuation?',parent=self.root):return
            path=f'/api/workers/{worker}/{action}'
        self.submit(lambda:self.api(path,{}),lambda _:self.action_done(action))
    def action_done(self,action):self.status.set(action.title()+' requested.');self.refresh()
    def close(self):
        self.closed=True;self.pool.shutdown(wait=False,cancel_futures=True);self.root.destroy()
    def run(self):self.root.mainloop()
if __name__=='__main__':ControlCenter().run()

# 18장 보안 규칙 테스트. 실행: firebase emulators:exec --only firestore --project carplan-htb "python3 -I tools/rules_test.py"
# 에뮬레이터는 서명 없는 JWT(alg none)를 받아서 임시 계정 셋(A 사람, B 사람, C 차)으로 23가지 허용/거부를 확인한다.
import json, sys, urllib.request, urllib.error, time
import base64, os
PROJ='carplan-htb'; HOST=os.environ.get('FIRESTORE_EMULATOR_HOST','127.0.0.1:8080')
BASE=f'http://{HOST}/v1/projects/{PROJ}/databases/(default)/documents'
def b64(o): return base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip('=')
def fake_token(uid): return b64({'alg':'none','typ':'JWT'})+'.'+b64({'sub':uid,'user_id':uid,'aud':PROJ,'iss':f'https://securetoken.google.com/{PROJ}','iat':int(time.time()),'exp':int(time.time())+3600,'firebase':{'sign_in_provider':'anonymous'}})+'.'

def http(method,url,body=None,token=None):
    req=urllib.request.Request(url,data=json.dumps(body).encode() if body is not None else None,method=method)
    req.add_header('Content-Type','application/json')
    if token: req.add_header('Authorization','Bearer '+token)
    try:
        with urllib.request.urlopen(req,timeout=30) as r: return r.status, json.loads(r.read() or b'{}')
    except urllib.error.HTTPError as e: return e.code, {}
_n=[0]
def anon():
    _n[0]+=1; uid=f'user{_n[0]}abcdef'; return fake_token(uid),uid
def S(v): return {'stringValue':v}
def A(*vs): return {'arrayValue':{'values':[S(v) for v in vs]}}
def I(n): return {'integerValue':str(n)}
def doc(path,fields,tok,method='PATCH',mask=None):
    url=f'{BASE}/{path}'+('?'+'&'.join(f'updateMask.fieldPaths={m}' for m in mask) if mask else '')
    return http(method,url,{'fields':fields},tok)[0]
def create(path,fields,tok): return doc(path,fields,tok,'PATCH',mask=list(fields))  # PATCH on a missing doc creates it
def get(path,tok): return http('GET',f'{BASE}/{path}',None,tok)[0]
def lst(path,tok): return http('GET',f'{BASE}/{path}',None,tok)[0]
def dele(path,tok): return http('DELETE',f'{BASE}/{path}',None,tok)[0]
tA,A_=anon(); tB,B_=anon(); tC,C_=anon()
G=f'groups/rt-{A_[:6]}'; E=f'{G}/events/e1'; R=f'{E}/days/2026-10-30/records/r1'; CAR=f'cars/{C_}'
results=[]
def t(name,got,want): results.append((name,got,want)); print(('OK ' if got==want else 'XX '),name,got,'(원함',want,')')
t('A 그룹 만들기', create(G,{'name':S('rules test'),'memberIds':A(A_),'ownerUid':S(A_),'inviteCode':S('ZZTEST'),'solo':{'booleanValue':False}},tA),200)
t('B 그룹 문서 읽기(초대 코드용)', get(G,tB),200)
t('B 멤버 아닌데 일정 목록', lst(f'{G}/events',tB),403)
t('B 멤버 아닌데 그룹 이름 바꾸기', doc(G,{'name':S('hack')},tB,mask=['name']),403)
t('B 코드로 참여(나만 추가)', doc(G,{'memberIds':A(A_,B_)},tB,mask=['memberIds']),200)
t('B 남 몰래 끼워 넣기+이름 동시', doc(G,{'memberIds':A(A_,B_,'stranger'),'name':S('x')},tB,mask=['memberIds','name']),200)  # 멤버라 허용
t('B(멤버) 그룹장 내보내기 시도', doc(G,{'memberIds':A(B_,'stranger')},tB,mask=['memberIds']),403)
t('B(멤버) 일정 만들기', create(E,{'title':S('t'),'startDate':S('2026-10-30'),'endDate':S('2026-10-30')},tB),200)
t('B 나가기(나만 빼기)', doc(G,{'memberIds':A(A_,'stranger')},tB,mask=['memberIds']),200)
t('B 나간 뒤 일정 읽기', get(E,tB),403)
t('C 차 문서 만들기(차주 A)', create(CAR,{'ownerUid':S(A_),'linkCode':S('000000')},tC),200)
t('C(차) 차주 그룹 일정 읽기', get(E,tC),200)
t('C(차) 기록 칸 쓰기', create(R,{'note':S('hi')},tC),200)
t('B 남의 차 설정 바꾸기', doc(CAR,{'fuelAlertPercent':I(10)},tB,mask=['fuelAlertPercent']),403)
t('A(차주) 차 설정 바꾸기', doc(CAR,{'fuelAlertPercent':I(10)},tA,mask=['fuelAlertPercent']),200)
t('C(차) 차주 유저 문서 쓰기', doc(f'users/{A_}',{'name':S('owner')},tC,mask=['name']),200)
t('B 남의 유저 문서 쓰기', doc(f'users/{A_}',{'name':S('hack')},tB,mask=['name']),403)
t('B 유저 문서 읽기', get(f'users/{A_}',tB),200)
t('B 내 친구 목록에 A', create(f'users/{B_}/friends/{A_}',{'x':I(1)},tB),200)
t('B A의 친구 목록에 나', create(f'users/{A_}/friends/{B_}',{'x':I(1)},tB),200)
t('B A의 친구 목록에 남', create(f'users/{A_}/friends/zzz',{'x':I(1)},tB),403)
t('B 나 없는 그룹 만들기', create(f'groups/rt-bad-{B_[:6]}',{'name':S('x'),'memberIds':A(A_),'ownerUid':S(B_)},tB),403)
t('A(그룹장) 그룹장 아닌 멤버 내보내기', doc(G,{'memberIds':A(A_)},tA,mask=['memberIds']),200)
# cleanup
for p,tok in [(R,tA),(E,tA),(G,tA),(CAR,tC),(f'users/{B_}/friends/{A_}',tB),(f'users/{A_}/friends/{B_}',tB)]: dele(p,tok)
bad=[r for r in results if r[1]!=r[2]]
print(f'\n{len(results)-len(bad)}/{len(results)} 통과'); sys.exit(1 if bad else 0)

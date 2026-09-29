# 워크플로우

조각을 진행하며 실제로 굳어진 작업 방식. 지금·다음은 [roadmap.md](roadmap.md), 개념은 [map.md](map.md), 이야기와 조각 상태는 [devlog/](devlog/README.md).

## 조각 하나의 흐름

1. **조각 고르기**: 지금 이야기의 devlog 파일에서 다음 조각. 이야기의 한 칸이고, 게임 안에서 확인되는 크기.
2. **사실 확인**: 외부 라이브러리·모드의 동작은 **추측하지 않고 소스·문서·검색으로 확인**한다. 결정에 쓰인 사실은 출처와 함께 결정 기록에 남긴다.
   (예: FTB Quests가 퀘스트북 전체를 동기화함, GeckoLib은 모델 파일이 없으면 예외를 던짐)
3. **결정**: 선택지 + 추천 + **고치는 비용**을 쉬운 말로 보여주고, 결정은 사용자가 한다. 사용자의 다른 아이디어가 더 나은 경우가 많으니 열어둔다.
   (예: 에디터에서 NPC 먼저 등록, 메이플식 여러 배치, NPC 문서 분리)
4. **구현**: 코드 + 에디터. **테스트는 비싼 것(저장 형식·id)에만** 붙인다.
   유닛 테스트에서 마인크래프트 NBT(`CompoundTag`·`NbtUtils`)는 게임 부트스트랩 없이 쓸 수 있다(예: `QuestItemsTest`).
   `ItemStack`처럼 레지스트리가 필요한 것은 안 되니, 비싼 규칙은 NBT·글자만 다루는 순수 함수로 떼어 테스트한다.
5. **기록**: 비싼 결정은 `decisions/`, 개념은 `map.md`, 이야기와 조각 상태는 `devlog/NNN-….md`, 지금·다음은 `roadmap.md`, 코드 구조는 `CLAUDE.md`.
6. **로컬 검증**: 에디터는 `npm run build` 뒤 **가짜 API 서버**로 눌러 본다: `<Python 3.14> tools/mock_server.py`(경로는 CLAUDE.md "Python")
   → `http://127.0.0.1:5174`(게임 없이 `editor/dist/index.html`과 고정 응답, publish 본문은 `build/mock/`에 저장).
   Java는 에이전트 환경에서 컴파일하지 않는다.
7. **커밋**: 코드와 문서를 나눠 커밋. conventional commits(영어) + 공동 작성자 Claude 줄.
8. **CI**: push 후 `gh run view <id> --json conclusion`으로 성공을 확인하고, **실행된 테스트 개수**도 확인한다:
   `gh run download <id> -n test-reports -D <폴더>` 뒤 `<폴더>/test/index.html`의 `id="tests"`·`id="failures"` 숫자.
   **연달아 push하면 앞 실행은 취소된다**(`cancelled`). 그때는 같은 코드가 들어 있는 **최신 커밋의 실행**으로 판정하고, 보고에
   "앞 실행은 취소, 코드가 같은 `<커밋>`에서 성공"이라고 적는다. 기다리는 동안 다른 일을 하려면 확인 명령을 `run_in_background`로.
9. **게임 확인**: 아래 체크리스트 형식으로 절차를 드리고 사용자가 실행한다.
   확인에 **시험 콘텐츠**가 필요하면(에디터에 아직 없는 칸, 이야기 전의 시험 줄) Claude가 넣는다. 시험 퀘스트는 이름에 "시험"을 넣고,
   넣기 전에 `run/config/lorebench/`의 문서를 스크래치패드에 복사해 둔다. 확인이 끝나거나 이야기 조립 때 **사용자에게 물은 뒤** 걷어내고,
   복사본과 `diff`로 시험 흔적만 빠졌는지 본다(예: `기다림 시험`, 농부 인사말의 타이핑 시험 줄).
10. **완료 기록**: 그 이야기의 devlog 파일에 완료와 확인 내역을 적는다.

## 조각 완료의 정의

- CI 성공 + 테스트 개수 확인(실패 0)
- 게임 확인 체크리스트 전부 통과
- 이야기의 devlog 파일에 완료·확인 내역 기록
- 비싼 결정이 있었다면 `decisions/`에 기록

## 게임 실행 환경

- IntelliJ에서 `runServer` + `runClient1`(Dev1) / `runClient2`(Dev2), 클라이언트는 `localhost`로 접속한다. 플레이어별 동작(공개 등)은 두 명으로 확인한다.
- 모두 `run/` 아래(git 밖): 서버 = `run/`(Lorebench 데이터 `run/config/lorebench/`), 클라이언트 = `run/client1/`, `run/client2/`.
- 리소스팩은 `run/resourcepacks/` 하나를 두 클라이언트가 함께 쓴다(`--resourcePackDir`).
- **처음부터 다시 시험하기**: 서버를 끄고 Gradle `lorebench > reset`. Lorebench 콘텐츠(그래프·NPC·퀘스트)와 기록(표식·NPC 배치·퀘스트 상태·진행)을 `run/config/lorebench/backups/<시각>/`으로 옮긴다. 월드와 인벤토리는 그대로.
  - 기록만 바뀌었을 때(키 규칙 등) 콘텐츠를 살리려면 reset 뒤 백업의 `npcs.json`·`quests.json`(·`graphs.json`)을 제자리로 복사한다. NPC 배치는 기록이라 다시 소환한다.
  - **한 사람의 한 퀘스트만** 다시 하려면 reset 대신 에디터 퀘스트 화면의 **Players → [Reset]**(받기 전으로, 서버를 끄지 않아도 됨).
- **형식이 바뀔 때 콘텐츠 살리기**: 새 코드는 옛 모양의 파일을 읽지 못하고, 읽기에 실패하면 NPC·그래프까지 아무것도 돌지 않는다
  (`LorebenchRuntime.load`). 그 상태의 에디터는 빈 문서를 보여 주고, publish하면 덮어쓴다. 초기화 대신 콘텐츠를 살리려면 **서버가
  꺼진 사이** 스크래치 스크립트로 파일을 새 모양으로 한 번 옮기고, 원본은 `backups/`에 둔다(예: 0015의 `backups/before-stages/`).
  모드 안 변환 코드는 여전히 첫 공개 뒤에(CLAUDE.md).
- **에디터 입력을 Claude에게 맡기기**: 사용자가 맡기면 사용자 서버의 에디터(`http://localhost:8080`)를 브라우저 패널로 조작해 NPC·퀘스트를 입력하고 publish한다. 끝나면 `/api/npcs`·`/api/quests`로 다시 읽어 확인한다. 사용자 브라우저에 에디터가 열려 있었다면 새로고침해야 한다(옛 화면에서 publish하면 덮어씀). 게임 쪽(소환·플레이)은 사용자 몫. 화면에 칸이 없거나 한꺼번에 고칠 때는 아래 "에디터 API로 작업하기".
  브라우저 패널 요령:
  - "+ NPC"·"+ Quest"·"+ Folder"의 이름 입력(`window.prompt`)은 패널에서 곧바로 닫힌다 → 그 탭에서
    `window.prompt = () => '이름'`을 실행한 뒤 누른다(소스는 안 바꿈, 새로고침하면 원래대로).
  - 좌표 클릭이 입력 칸에 안 닿을 때가 있다 → `find`로 찾은 ref로 클릭·입력한다. 숫자 칸은 JS로 `focus()`+`select()` 뒤 입력,
    선택 칸(select)은 `form_input`이 먹는다.
  - 칸 비우기는 세 번 클릭 + Delete가 안 먹을 때가 있다 → 클릭 + `ctrl+a` + BackSpace.
  - 흐린 예시 글자(placeholder)를 입력값으로 오독하지 말고, 넣은 값은 JS로 `value`를 읽어 확인한다.
  - 버튼·`window.confirm`도 그 탭에서만 대신 채운다(`window.confirm = (m) => m.includes("'지울 것'")`처럼 대상을 확인하는 식으로).
  - 패널이 가려져 있으면(화면 크기 0) 좌표 클릭이 안 되고, 포커스 이벤트와 `requestAnimationFrame`이 멈춘다. 스크립트로
    `focus()`·`click()`하고 입력은 `document.execCommand('insertText', …)`(React가 실제 타이핑처럼 받음)로 한다. 에디터 코드가
    포커스 이벤트나 rAF에 기대면 여기서 드러난다(표시 버튼이 엉뚱한 줄에 들어간 버그, 조각 3).
  - 드래그 앤 드롭과 우클릭은 이벤트를 직접 만든다: `el.dispatchEvent(new DragEvent('dragstart'|'dragover'|'drop', {bubbles: true,
    cancelable: true, dataTransfer: new DataTransfer(), clientY}))`(한 `DataTransfer`를 끝까지 같이 씀, 이벤트 사이에 잠깐 기다림),
    우클릭은 `new MouseEvent('contextmenu', {bubbles: true, cancelable: true, clientX, clientY})`(조각 4의 단계 트리).
  - **서버를 다시 켜지 않고 새 에디터 보기**: `npx vite build --outDir <스크래치패드>/editor-build`로 빌드하고, 그 `index.html`을
    내어 주고 `/api/*`는 8080으로 넘기는 표준 라이브러리 파이썬 서버를 8090에 띄운다(Bash `run_in_background`, 끝나면 `TaskStop`).
    publish까지 실제 서버로 간다. 시험 입력은 끝나면 되돌리고, 먼저 복사해 둔 `npcs.json`·`quests.json`과 `diff`로 같은지 본다.

## 에디터 API로 작업하기

에디터 화면 대신 에디터 서버의 API(`http://localhost:8080/api/…`, 목록은 `web/LorebenchWebServer.java` 맨 위 주석)를 직접 쓴다.

- **언제**: 형식에 생겼지만 에디터에 아직 칸이 없는 필드(예: 조각 2의 NPC `voice`), 여러 문서를 한꺼번에 고칠 때, 상태를 읽을 때
  (`/api/quest-players?quest=…`로 누가 어느 상태인지 → 어떤 인사말 묶음이 나올지 판단).
- **어떻게**: 스크래치패드에 표준 라이브러리 파이썬(`urllib`)으로 `.py`를 쓴다. `GET /api/graphs`·`/api/npcs`·`/api/quests`로 세 문서를
  받아 필요한 곳만 고치고, 셋을 함께 `POST /api/publish`(에디터의 Publish와 같은 요청: 세 문서를 통째로 바꾼다). 응답의
  `accepted`와, 다시 읽은 문서나 `run/config/lorebench/*.json`으로 확인한다. 한글은 `ensure_ascii=False` + UTF-8, 출력은
  `PYTHONIOENCODING=utf-8`.
- **주의**:
  - 사용자 브라우저나 패널에 에디터가 열려 있었다면 **새로고침한 뒤** publish하라고 알린다. 옛 화면은 방금 넣은 것을 몰라서 덮어쓴다.
  - publish는 인증이 없고 되돌리기가 없다. 고치기 전에 문서를 복사해 둔다(조각 흐름 9).
  - `POST /api/quest-reset`은 한 사람의 기록을 지운다. 사용자가 요청할 때만 쓴다.

## 게임 확인 체크리스트 형식

- 번호 매긴 절차. 각 항목에 **무엇을 하면 → 무엇이 보여야 하는지**.
- 실패·되돌리기 경로도 포함(예: 리소스팩을 끄면 스티브로 보여야 함, 참조 중인 NPC 삭제는 거부돼야 함).
- 명령어 권한 등 전제 조건을 맨 앞에.
- 핵심 항목에는 **로그·기록에 남는 증거**를 적는다(예: 서버 로그 `Moved 1 player(s)`, Players의 단계). 사용자가 완료라고 하면
  그 증거를 한 번 본다(일곱 번째 이야기 조각 3: 핵심 확인이 빠진 것을 로그로 찾아 확인 전용 퀘스트로 다시 함).
- 절차가 **앞 조각의 검사 규칙**에 걸리지 않는지 본다(예: `from` 없는 목표가 있는 단계를 맨 앞으로 옮기라고 해서 publish가 거부됨).

## 문제가 생기면

- **추측보다 증거가 먼저.** 게임 쪽 문제는 `run/logs/latest.log`(서버)부터 본다. 클라이언트 쪽(화면·렌더링)은 `run/client1/logs/`, `run/client2/logs/`.
  (예: "NPC 둘 다 사라짐"은 거리 문제가 아니라 로그상 `remove chief`(전부 삭제) 실행이었다)

## 설계할 때 확인할 것

- **순서**(다섯·여섯 번째 이야기에서 굳어짐): 장면 초안(사용자 확인) → **정할 것 표**(무엇 / 고치는 비용) → **비싼 것부터 하나씩**
  (후보 + 다른 사례 비교표 + 추천, 사용자가 정하면 다음으로) → 싼 것은 **추천 표 하나**(바꿀 번호만 받음) → 결정 기록 + devlog
  파일(장면·조각) + roadmap "지금 하는 이야기"를 **한 커밋**으로. 설계 도중 나온 사용자의 다음 아이디어(예: 속삭임, BGM)는 그 자리에서 roadmap B에 적는다.

- **파괴적인 동작은 이름부터 다르게.** 인자 하나 차이로 "하나 삭제"와 "전부 삭제"가 갈리면, 자동완성으로 실수가 난다.
- 사용자 편의를 위해 계약을 몰래 어기지 않는다(v2 교훈: "주체 명시" 계약인데 코드는 암묵 대상으로 동작했다).
- **장면 초안이 이미 있는 규칙과 부딪치지 않는지** 본다. 예: 대화는 "할 일(건네기·제안)이 먼저, 없으면 인사말"이라, 네 번째
  이야기 초안의 "받기 전 인사말"은 제안이 있어 실제로는 나오지 않았다(조립 단계에서야 발견).
- **메시지 버전**: 클라이언트와 서버가 주고받는 메시지(퀘스트 목록 `QuestSyncPayload`, 대화 `DialoguePayload` 등)에 칸을
  더하거나 순서를 바꾸면 등록부의 숫자(`registrar("3")`)를 올린다. 숫자가 다르면 접속할 때 NeoForge가 "버전이 다르다"며
  막아 준다(`NetworkComponentNegotiator`). 올리지 않으면 옛 모드를 쓰는 사람이 들어와서 메시지를 잘못 읽고 튕긴다.

## 에셋 작업 (Blockbench MCP → GeckoLib)

에셋은 코드와 따로 가는 **협업 작업**이다. 에셋 담당 에이전트 [`asset-artist`](../.claude/agents/asset-artist.md)(모델 Opus)가
맡고, Blockbench 요령·도구의 특이점·파일 위치·백업은 그 파일에만 적는다(사용자 방식, 2026-09-29).

1. **주문서**(설계, 조각 0): 장면에서 필요한 에셋을 뽑아 이야기 파일의 "에셋" 칸에 적고 사용자에게 확인받는다(형식은
   [devlog/README.md](devlog/README.md)). 애니메이션 **이름**은 대사(`<play=…>`)와 NPC 문서가 가리켜 **비싸니** 여기서 정한다.
   새 모델이면 외형 / 성능 / 균형 중 무엇을 우선할지 먼저 묻는다.
2. **맡기기**(조각 1을 시작할 때): `asset-artist`를 뒤에서 부르고(이야기 파일 경로와 주문서를 넘김) Claude는 코드를 한다.
   Blockbench는 하나라 **에셋 에이전트는 한 번에 하나**. 이야기 조립 전에 끝나 있으면 된다.
3. **확인**: 보고만 믿지 않고 **내보낸 파일**(애니메이션 개수·형식 버전)과 보고의 확인 시각대로 **Blockbench 스크린샷**(시작·절정·끝)을
   Claude가 직접 본다. 에이전트가 돌려준 질문은 사용자에게 묻는다.
4. **검수**: 스크린샷을 사용자에게 보여 느낌을 통과받고, 주문서의 상태를 "통과"로 바꾼다. 게임 안 확인은 이야기 조립 때.
5. **요령 옮기기**: 에이전트가 새로 찾은 요령은 `asset-artist.md`의 "요령"·"특이점"에 옮긴다(예: 회전 부호, `shocked`).

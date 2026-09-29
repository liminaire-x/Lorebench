# CLAUDE.md — Lorebench 개발 가이드 (AI 어시스턴트용)

**Lorebench**: NeoForge 마인크래프트 모드. 웹 에디터에서 NPC·퀘스트(콘텐츠)와 노드 그래프(로직)를
만들고 **publish → 서버 반영**. 클라이언트+서버 모드(GeckoLib 애니메이션, 자체 퀘스트 화면).
방향: 취미로 지속 개발, 점차 규모 있는 프로젝트로 성장. 채택을 성공의 전제로 삼지 않음.

이 파일은 매 세션 로드되는 **짧은 가이드**다. 개념은 [docs/map.md](docs/map.md), 이야기(장면·조각·확인
기록)는 [docs/devlog/](docs/devlog/README.md)에 한 파일씩, 지금 하는 이야기와 다음 후보는 [docs/roadmap.md](docs/roadmap.md)에만 있다.

## 지금 방향이 나온 이유
- **이야기 중심**: v2 설계가 AI 주도로 사용자가 소화하는 속도보다 빨리 두꺼워져, 사용자가 도메인 감을
  잃었다. 그래서 2026-09-23 **이야기(구체 시나리오) 중심으로 다시 시작**했다. 옛 코드·문서는 git 태그
  `legacy-v2`, 형제 폴더 `../Colophon-legacy`(worktree, 폴더를 옮기면 `git worktree repair`)에 보관.
  옛 코드는 필요해질 때 사용자가 이해·동의한 파일만 다시 가져온다. 옛 문서는 반면교사로 읽되 기준으로
  삼지 않는다.
- **FTB Quests 대신 자체 퀘스트**: FTB Quests(1.21.1)는 로그인 때 **퀘스트북 전체**를 모든 클라이언트에
  보낸다(`SyncQuestsMessage`). "숨김"은 화면에서 가릴 뿐이라 스토리가 새어 나간다. Lorebench는 서버가
  **공개된 퀘스트만 그 플레이어에게** 보낸다.
- **GeckoLib**: NPC에 모델·애니메이션(손 내밀기, 기뻐하기 등)을 입히려고 쓴다. GeckoLib은 클라이언트에
  모드와 에셋이 있어야 하므로 Lorebench는 **클라이언트+서버 모드**다.

## 코드 구조 (`kr.guinnessgroup.lorebench`)
- `graph/` — 저장된 그래프 문서(`GraphDoc`)와 그 읽기·쓰기(`GraphFormat`, format 1). 모양만 검사.
- `runtime/` — 노드 종류(`NodeType`·`Field`·`NodeRegistry`), 문서 → 실행 그래프(`GraphBuilder`), 실행(`Runner`, 즉시·동기), 발행·트리거(`LorebenchRuntime`).
- `record/` — 기록(`Owner`·`RecordStore` 캐시·`H2RecordBackend`, schema 1).
- `npc/` — NPC 정의 문서(`NpcFormat`, format 1)·배치(`Placement`, 서버 기록)·엔티티(`NpcEntity`: 애니메이션 층 = 평소/대화 한 벌 위에 한 번·반복 줄, 0011)·명령어·`Npcs`(게임 쪽 창구).
- `quest/` — 퀘스트 문서(`QuestDoc`·`QuestFormat`, format 1: 한 번뿐인 것 + 단계 목록 `Stage`, 0015)·상태(`QuestState`)·지금 단계 기록(`QuestStages`: 단계 id, 없으면 첫 단계)·진행 기록(`QuestProgress`, `<종류>:<대상>`)·거절 횟수 기록(`QuestDeclines`)·맡긴 날 기록(`QuestWaits`: 게임 날짜, 기다림이 끝났나)·퀘스트 전용 아이템(`QuestItems`: 주인 표식, `QuestItemEntity`: 주인에게만 보이는 떨어진 조각)·`Quests`(게임 쪽 창구: 세기·공개·완료·되돌리기)·`Crops`·`Breeding`(수확·번식 목표가 받는 것)·`QuestSyncPayload`(공개된 퀘스트만 그 플레이어에게). 대화: `Dialogue`(순수 로직: 할 이야기 계산)·`Dialogues`(서버 쪽 대화, 선택 재확인)·`DialoguePayload`/`DialogueChoicePayload`.
- `nodes/` — 빌트인 노드. 노드당 파일 하나, `BuiltinNodes.registerAll`.
- `client/` — 클라이언트 전용(`NpcRenderer`: GeckoLib 모델 있으면 그것, 없으면 스티브, 대화 중 돌아보기는 그릴 때만 / `NpcGeoModel`: 리소스 경로 규칙 / `QuestScreen`: `J` 퀘스트 화면 / `DialogueScreen`: 대화창 / `Typing`: 한 페이지의 타이핑(표시에 따라 속도·쉼·동작) / `QuestCard`: 필요한 것·보상 그리기(둘이 공유) / `ClientQuests`·`ClientDialogue`: 받은 것 보관). `FMLEnvironment.dist == CLIENT`일 때만 로드.
- `web/` — 에디터 서버. `Lorebench.java` — 부트스트랩·게임 이벤트 연결. `LorebenchConfig` — `serverName`. `Folders` — 세 문서가 함께 쓰는 에디터 폴더(읽기·쓰기·검사). `DialogueLines` — 대사(한 줄 = 한 페이지) 읽기·쓰기. `Speech` — 대사 칸 하나(줄, 또는 조건 묶음: 위에서부터 처음 맞는 것). `Cues` — 대사 글 안의 꺾쇠 표시 읽기·검사(0014, 서버 검사와 클라이언트 타이핑이 공유).
- 결정 기록: [0001 저장 형식](docs/decisions/0001-storage-format.md), [0002 NPC](docs/decisions/0002-npc.md), [0003 NPC 외형](docs/decisions/0003-npc-looks.md), [0004 id·기록 키](docs/decisions/0004-ids-and-record-keys.md), [0005 퀘스트](docs/decisions/0005-quests.md), [0006 아이템 표기](docs/decisions/0006-item-syntax.md), [0007 이름](docs/decisions/0007-rename-lorebench.md), [0008 폴더](docs/decisions/0008-quest-folders.md), [0009 퀘스트 작업대](docs/decisions/0009-quest-workbench.md), [0010 수확·번식 목표·지급품](docs/decisions/0010-farming-goals.md), [0011 대화 몸짓](docs/decisions/0011-talk-gestures.md), [0012 목걸이: 전용 아이템·거절 기억·대사 조건](docs/decisions/0012-lost-necklace.md), [0013 기다림](docs/decisions/0013-waiting.md), [0014 타이핑: 글 안 표시·글자 소리](docs/decisions/0014-typing.md), [0015 퀘스트 단계](docs/decisions/0015-quest-stages.md).

## 설계 약속 (꼭 지킬 것)
- **"잠김" 대신 고치는 비용**(비쌈 = 저장 형식·id·공개 API / 중간 / 쌈 = 내부 코드)을 표시한다.
- **사용자가 쉬운 말로 설명할 수 없는 결정은 확정하지 않는다.** 선택지 + 추천을 주고 결정은 사용자.
- **이야기가 요구하는 것만** 설계한다. 쓰는 곳이 없는 타입·기능을 미리 설계하지 않는다.
- 한 번에 하나씩, 문서는 작게. 같은 사실은 한 곳에만.
- 배경: 사용자는 설계·기획 전공이 아니라고 밝혔고, 작은 결정이 나중에 부메랑이 될까 봐 모든 결정을 첫
  단추처럼 다룬다. 쉬운 말로, 되돌리는 비용부터. 설명이 안 통하면 비유와 구체적 장면으로 다시 쓴다.
  다이어그램을 요청하면 `show_widget`으로 그린다.
- 사용자는 플레이어 편의보다 **작가의 연출을 지키는 쪽**을 자주 고른다(플레이어 글자 속도 설정 없음, 기다리는 동안 아무것도 안 보이기,
  약간 불친절한 툴팁). 추천할 때 그 선택지를 빼지 않되, 단정하지 말고 매번 묻는다. 사례는 한 게임에 기대지 말고 여러 게임을 고루 든다.
- 결정을 도울 때 **다른 사례(게임·엔진·도구) 비교표**를 준다: 사례 / 모양 / "우리는 어느 쪽 + 나중에 무엇이 바뀌나".
  사용자는 사례에서 큰 방향을 스스로 끌어낸다. 개념을 설명할 때는 활용처를 "지금 · 이미 비슷한 것 · 나중"으로 나눠 보인다.
  사용자가 설계를 일반화하면(예: `reoffer` 칸 → 조건 if/else) 맞다고 분명히 하고 A/B 비교로 다시 정리한다.
- **개발 단계(첫 공개 전)엔 변환 대신 초기화**: 형식·키 규칙이 바뀌면 변환 코드를 쓰지 않고
  `run/config/lorebench/`를 초기화한다(무엇을 지우는지 먼저 알림). 그래서 비싼 이유가 "옛 로컬 데이터
  변환"뿐이면 지금은 싸다. 첫 공개 뒤엔 형식 번호 + 변환.

## 스택 / 사실
- NeoForge **1.21.1** / Java **21**. mod_id `lorebench`, group `kr.guinnessgroup`, **MPL-2.0**(파일 단위).
- git author: **liminaire-x <gntodtndls156@gmail.com>**. 커밋 = **conventional commits** (`feat(...)`, `docs:` …). 커밋 메시지 끝에 **공동 작성자 Claude** 줄을 넣는다(`Co-Authored-By: Claude … <noreply@anthropic.com>`).
- **커밋 전 `git diff --cached --stat`**: 사용자가 병행 편집·스테이징한다. 남의 스테이징분이 있으면 경로 지정 커밋(`git commit -- 파일`)으로 내 파일만(실제로 사용자의 이미지 삭제가 섞여 push된 적 있음).
- 응답/문서 언어: **한국어**. git commit 언어: **영어**. Lorebench(로어벤치)는 모음으로 끝난다 → "Lorebench**는/를/가/와**".
- 저장소: 로컬 `C:\Users\gntod\MyProjects\Intellij\LoreBench`, 원격 `https://github.com/liminaire-x/Lorebench.git`. Gradle 프로젝트 이름은 `settings.gradle`에서 `lorebench`로 고정(폴더 이름과 무관).
- **인수인계 문서 `docs/handoff.md`는 로컬 전용**: 커밋하지 않는다(`.gitignore`에 있음).
- 프런트: React + React Flow(@xyflow/react), Vite 단일 index.html. `editor/` 소스 → Gradle buildEditor/packEditor로 패키징. 헤더 탭: `App.jsx`(그래프 탭·Publish·문서 상태), `QuestTab.jsx`(퀘스트 트리·편집), `Stages.jsx`(트리의 퀘스트 안 단계: 브라우저 내장 드래그 앤 드롭·우클릭 메뉴, 0015), `NpcTab.jsx`(NPC 트리·섹션), `FolderTree.jsx`(세 탭이 함께 쓰는 폴더 트리·폴더 편집), `Section.jsx`(카드·대사 줄 목록·조건 묶음 편집 `SpeechEditor`). publish 직전 빈 줄·빈 조건 정리는 `App.jsx`의 `tidyNpc`·`tidyQuest`(`realSpeech`).
- 웹 서버: JDK `HttpServer` 8080 (`web/LorebenchWebServer.java`).
- **에이전트 환경에서 Java 컴파일 금지**(NeoForge 빌드가 무겁고 Windows Gradle 캐시와 꼬임). 컴파일/실행 확인은 **GitHub Actions CI**(push 시 클린 리눅스 빌드) 또는 **사용자 IntelliJ**(`runServer` + `runClient1`/`runClient2`, 두 플레이어 Dev1·Dev2).

## 개발 워크플로우
자세한 내용(조각 흐름·완료 정의·게임 확인 형식·에셋 작업)은 [docs/workflow.md](docs/workflow.md). 요약:
1. **조각 단위로 진행**한다(지금 이야기는 [docs/roadmap.md](docs/roadmap.md), 조각은 그 이야기의 devlog 파일). 각 조각은 게임 안에서 확인된다.
2. **외부 동작은 소스·문서로 확인**하고 추측하지 않는다. 결정은 선택지 + 추천 + 고치는 비용 → 사용자.
3. 검증:
   - **CI = 컴파일 게이트**. 커밋 → master push → 사후 CI 확인, 실패 시 fix-forward. 성공 판정은 **`gh run view <id> --json conclusion`으로 명시 확인**(`gh run watch` exit 코드만 믿지 말 것, 실패를 green으로 오독해 보고한 적 있음) + 테스트 리포트에서 **실행 개수** 확인(세는 법, 연달아 push해 앞 실행이 취소됐을 때의 판정은 [workflow](docs/workflow.md) 조각 흐름 8).
   - **IntelliJ = 동작 게이트**. 번호 매긴 게임 확인 체크리스트를 드리고, 사용자 확인까지 받고 다음 조각으로.
   - **테스트는 비싼 것에 붙인다**: 저장 형식·id처럼 깨지면 데이터가 손상되는 곳은 처음부터 테스트 동반.
4. 게임 쪽 문제는 **`run/logs/latest.log`부터** 본다. 추측보다 증거.
5. 에셋(NPC 모델)은 에셋 담당 에이전트 `.claude/agents/asset-artist.md`(Blockbench MCP + `.claude/skills/`)에 이야기 파일의 **에셋 주문서**로 맡기고, Claude는 코드를 한다(흐름은 workflow "에셋 작업"). 에셋 파일은 저장소에 넣지 않는다.
6. 호출부 파악은 grep/read + CI 컴파일로 한다.

## 도구 환경 (에이전트용)
- **기록은 이 파일에만** 한다. Claude 메모리(`~/.claude/projects/…/memory`)는 쓰지 않는다.
- **마인크래프트·NeoForge API 확인**: `build/moddev/artifacts/neoforge-21.1.249-merged.jar`에 마인크래프트 `.java` 소스가 들어 있다(`unzip -p <jar> net/minecraft/…/X.java`). NeoForge 소스는 같은 폴더의 `neoforge-21.1.249-sources.jar`. javap·strings는 없다.
- **게임 데이터(git 밖)**: 실행 환경의 기본 경로는 [docs/workflow.md](docs/workflow.md) "게임 실행 환경". 그 밖에:
  - 테스트 리소스팩 `run/resourcepacks/lorebench-test/`: 에셋 `assets/lorebench/…`, 원본 `source/chief.bbmodel`·`source/chief-texture.mjs`. 서버·두 클라이언트의 `options.txt` 세 곳에서 켜져 있다.
- **Python**: PC에 설치된 3.14 `/c/Users/gntod/AppData/Local/Programs/Python/Python314/python.exe`(추가 패키지 없음, 표준
  라이브러리만). 쓰는 곳은 가짜 API 서버와 수정 스크립트뿐이다. Bash의 `python`은 PATH에 없다. 패키지를 새로 깔아야 하면 먼저 사용자에게 묻는다.
  - 오래 도는 것(가짜 API 서버 `tools/mock_server.py` 등)은 Bash `run_in_background`로 띄우고 **`TaskStop`으로 끈다**.
    `taskkill /IM python.exe`는 PC의 모든 파이썬(사용자의 다른 작업 포함)을 끈다(실제로 한 번 그랬다).
  - 가짜 서버가 `build/mock/`에 쓴 publish 본문에서 고정 응답에 있던 한글(NPC 이름 `농부`)은 깨져 보였다(에디터에서 입력한
    한글은 멀쩡함, 원인 미확인). 에디터 확인은 칸 구조만 본다.
  - 중간 단계 파이썬은 스크래치패드에 `.py`로 써서 실행하고 `if __name__ == '__main__':`를 둔다. 인라인 heredoc + `Remove-Item`을 한 PowerShell 호출에 이으면 조용히 실패한다.
- **셸 함정**: `sed` 치환에 `#` 구분자를 쓰면 `#minecraft:logs`, `## 제목`과 충돌한다. 파일 수정은 Edit 도구를 먼저, 셸 치환이 꼭 필요하면 `|` 구분자. 긴 파이썬 스크립트(특히 JSX·자바 코드가 든 수정 스크립트)는 **Bash heredoc으로 파일을 쓰는 것 자체**가 따옴표를 잘못 읽어 실패한다 → **Write 도구로** 스크래치패드에 `.py`를 쓰고 Bash로 실행(토큰은 거의 같고, 실패해 다시 쓰는 비용이 훨씬 큼). 수정은 `rep(old, new)` + `assert count == 1`로. 작업 사본은 CRLF라, 스크립트는 파일을 **줄바꿈을 가리지 않고 읽고**(`open(p, encoding='utf-8')`)
  **`newline='\n'`으로 쓴다**(그러지 않으면 여러 줄 `old`가 안 맞는다. git이 커밋 때 맞춰 준다). sed로 `x.isEmpty()` 같은 걸 바꿀 땐
  **`!`가 붙은 자리를 먼저 grep**한다(`!x.isEmpty()`가 `!x.size() == 0`이 되어 CI 컴파일이 실패한 적 있음).

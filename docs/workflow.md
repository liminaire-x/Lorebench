# 워크플로우

조각을 진행하며 실제로 굳어진 작업 방식. 상태는 [roadmap.md](roadmap.md), 개념은 [map.md](map.md), 이야기는 [stories.md](stories.md).

## 조각 하나의 흐름

1. **조각 고르기**: 로드맵에서 다음 조각. 이야기의 한 칸이고, 게임 안에서 확인되는 크기.
2. **사실 확인**: 외부 라이브러리·모드의 동작은 **추측하지 않고 소스·문서·검색으로 확인**한다. 결정에 쓰인 사실은 출처와 함께 결정 기록에 남긴다.
   (예: FTB Quests가 퀘스트북 전체를 동기화함, GeckoLib은 모델 파일이 없으면 예외를 던짐)
3. **결정**: 선택지 + 추천 + **고치는 비용**을 쉬운 말로 보여주고, 결정은 사용자가 한다. 사용자의 다른 아이디어가 더 나은 경우가 많으니 열어둔다.
   (예: 에디터에서 NPC 먼저 등록, 메이플식 여러 배치, NPC 문서 분리)
4. **구현**: 코드 + 에디터. **테스트는 비싼 것(저장 형식·id)에만** 붙인다.
   유닛 테스트에서 마인크래프트 NBT(`CompoundTag`·`NbtUtils`)는 게임 부트스트랩 없이 쓸 수 있다(예: `QuestItemsTest`).
   `ItemStack`처럼 레지스트리가 필요한 것은 안 되니, 비싼 규칙은 NBT·글자만 다루는 순수 함수로 떼어 테스트한다.
5. **기록**: 비싼 결정은 `decisions/`, 개념은 `map.md`, 이야기는 `stories.md`, 상태는 `roadmap.md`, 코드 구조는 `CLAUDE.md`.
6. **로컬 검증**: 에디터는 `npm run build` 뒤 **가짜 API 서버**로 눌러 본다: `<Python 3.14> tools/mock_server.py`(경로는 CLAUDE.md "Python")
   → `http://127.0.0.1:5174`(게임 없이 `editor/dist/index.html`과 고정 응답, publish 본문은 `build/mock/`에 저장).
   Java는 에이전트 환경에서 컴파일하지 않는다.
7. **커밋**: 코드와 문서를 나눠 커밋. conventional commits(영어) + 공동 작성자 Claude 줄.
8. **CI**: push 후 `gh run view <id> --json conclusion`으로 성공을 확인하고, **실행된 테스트 개수**도 확인한다:
   `gh run download <id> -n test-reports -D <폴더>` 뒤 `<폴더>/test/index.html`의 `id="tests"`·`id="failures"` 숫자.
9. **게임 확인**: 아래 체크리스트 형식으로 절차를 드리고 사용자가 실행한다.
10. **완료 기록**: 로드맵에 완료와 확인 내역을 적는다.

## 조각 완료의 정의

- CI 성공 + 테스트 개수 확인(실패 0)
- 게임 확인 체크리스트 전부 통과
- 로드맵에 완료·확인 내역 기록
- 비싼 결정이 있었다면 `decisions/`에 기록

## 게임 실행 환경

- IntelliJ에서 `runServer` + `runClient1`(Dev1) / `runClient2`(Dev2), 클라이언트는 `localhost`로 접속한다. 플레이어별 동작(공개 등)은 두 명으로 확인한다.
- 모두 `run/` 아래(git 밖): 서버 = `run/`(Lorebench 데이터 `run/config/lorebench/`), 클라이언트 = `run/client1/`, `run/client2/`.
- 리소스팩은 `run/resourcepacks/` 하나를 두 클라이언트가 함께 쓴다(`--resourcePackDir`).
- **처음부터 다시 시험하기**: 서버를 끄고 Gradle `lorebench > reset`. Lorebench 콘텐츠(그래프·NPC·퀘스트)와 기록(표식·NPC 배치·퀘스트 상태·진행)을 `run/config/lorebench/backups/<시각>/`으로 옮긴다. 월드와 인벤토리는 그대로.
  - 기록만 바뀌었을 때(키 규칙 등) 콘텐츠를 살리려면 reset 뒤 백업의 `npcs.json`·`quests.json`(·`graphs.json`)을 제자리로 복사한다. NPC 배치는 기록이라 다시 소환한다.
  - **한 사람의 한 퀘스트만** 다시 하려면 reset 대신 에디터 퀘스트 화면의 **Players → [Reset]**(받기 전으로, 서버를 끄지 않아도 됨).
- **에디터 입력을 Claude에게 맡기기**: 사용자가 맡기면 사용자 서버의 에디터(`http://localhost:8080`)를 브라우저 패널로 조작해 NPC·퀘스트를 입력하고 publish한다. 끝나면 `/api/npcs`·`/api/quests`로 다시 읽어 확인한다. 사용자 브라우저에 에디터가 열려 있었다면 새로고침해야 한다(옛 화면에서 publish하면 덮어씀). 게임 쪽(소환·플레이)은 사용자 몫.
  브라우저 패널 요령:
  - "+ NPC"·"+ Quest"·"+ Folder"의 이름 입력(`window.prompt`)은 패널에서 곧바로 닫힌다 → 그 탭에서
    `window.prompt = () => '이름'`을 실행한 뒤 누른다(소스는 안 바꿈, 새로고침하면 원래대로).
  - 좌표 클릭이 입력 칸에 안 닿을 때가 있다 → `find`로 찾은 ref로 클릭·입력한다. 숫자 칸은 JS로 `focus()`+`select()` 뒤 입력,
    선택 칸(select)은 `form_input`이 먹는다.
  - 칸 비우기는 세 번 클릭 + Delete가 안 먹을 때가 있다 → 클릭 + `ctrl+a` + BackSpace.
  - 흐린 예시 글자(placeholder)를 입력값으로 오독하지 말고, 넣은 값은 JS로 `value`를 읽어 확인한다.

## 게임 확인 체크리스트 형식

- 번호 매긴 절차. 각 항목에 **무엇을 하면 → 무엇이 보여야 하는지**.
- 실패·되돌리기 경로도 포함(예: 리소스팩을 끄면 스티브로 보여야 함, 참조 중인 NPC 삭제는 거부돼야 함).
- 명령어 권한 등 전제 조건을 맨 앞에.

## 문제가 생기면

- **추측보다 증거가 먼저.** 게임 쪽 문제는 `run/logs/latest.log`(서버)부터 본다. 클라이언트 쪽(화면·렌더링)은 `run/client1/logs/`, `run/client2/logs/`.
  (예: "NPC 둘 다 사라짐"은 거리 문제가 아니라 로그상 `remove chief`(전부 삭제) 실행이었다)

## 설계할 때 확인할 것

- **파괴적인 동작은 이름부터 다르게.** 인자 하나 차이로 "하나 삭제"와 "전부 삭제"가 갈리면, 자동완성으로 실수가 난다.
- 사용자 편의를 위해 계약을 몰래 어기지 않는다(v2 교훈: "주체 명시" 계약인데 코드는 암묵 대상으로 동작했다).
- **장면 초안이 이미 있는 규칙과 부딪치지 않는지** 본다. 예: 대화는 "할 일(건네기·제안)이 먼저, 없으면 인사말"이라, 네 번째
  이야기 초안의 "받기 전 인사말"은 제안이 있어 실제로는 나오지 않았다(조립 단계에서야 발견).
- **메시지 버전**: 클라이언트와 서버가 주고받는 메시지(퀘스트 목록 `QuestSyncPayload`, 대화 `DialoguePayload` 등)에 칸을
  더하거나 순서를 바꾸면 등록부의 숫자(`registrar("3")`)를 올린다. 숫자가 다르면 접속할 때 NeoForge가 "버전이 다르다"며
  막아 준다(`NetworkComponentNegotiator`). 올리지 않으면 옛 모드를 쓰는 사람이 들어와서 메시지를 잘못 읽고 튕긴다.

## 에셋 작업 (Blockbench MCP → GeckoLib)

스킬: `.claude/skills/`의 Blockbench 스킬. **`blockbench-use`를 먼저** 읽고 분야별 스킬(모델링·텍스처·애니메이션)로 넘어간다.

1. **새 모델이면 먼저 묻기**: 외형 / 성능 / 균형 중 무엇을 우선하나.
2. **형식**: `geckolib_model`(GeckoLib 플러그인), **박스 UV**. 그래야 geometry가 GeckoLib이 지원하는 `format_version 1.12.0`으로 나온다.
3. **텍스처**: UV 배치에 맞춰 스크립트(Node, 외부 라이브러리 없음)로 그리고, 스크립트도 원본으로 보관한다.
4. **애니메이션**:
   - 회전 부호는 스크린샷(정면·옆)으로 먼저 확인한다. Blockbench 안에서 X 양수는 **아래로 늘어진 부위(팔)를 앞으로**,
     **위로 뻗은 부위(머리, 발 기준의 몸 전체)는 뒤로 젖힌다**(머리 +X = 위를 봄, `shocked` 때 확인). 그래서 `happy`의 머리 -12는 살짝 아래를 본다.
   - 몸 전체를 젖힐 때는 `body`가 아니라 `rig_root`를 돌린다. `body`는 회전 중심이 목에 있어 엉덩이가 앞으로 빠진다.
   - **내보낸 파일은 X·Y 부호가 뒤집혀 있다**(Blockbench +70 → `.animation.json` -70). 내보낸 파일의 값을 Blockbench에
     그대로 넣으면 팔이 뒤로 간다. 기존 애니메이션을 참고할 때는 Blockbench 안의 값을 읽는다.
   - 애니메이션 격자(`snapping`)를 키 간격에 맞춘다(0.05초 단위면 20fps). 기본 24fps면 0.8초가 0.7917초로 밀려 반복 이음새가 어긋난다.
   - 시작·중간·끝 자세와 반복 이음새를 확인한다. 시작 자세 하나로는 동작을 검증할 수 없다.
5. **내보내기**:
   - geometry는 `export_model`의 `bedrock` 코덱으로 뽑는다(X 부호 반전은 Bedrock 규칙이고 GeckoLib이 되돌린다).
   - 애니메이션은 형식의 애니메이션 코덱 `compileFile`로 뽑는다(`format_version 1.8.0`).
   - 편집 가능한 `.bbmodel`도 함께 저장한다.
6. **배치**: 리소스팩의 `assets/lorebench/{geo,animations,textures}/npc/<model>`. 테스트 팩은 `run/resourcepacks/lorebench-test/`(git 밖, 에셋은 저장소에 넣지 않는다).

**Blockbench 도구의 특이점**
- `place_cube`는 텍스처가 먼저 있어야 한다(없으면 `No texture found`).
- `create_animation`은 이름 앞에 `animation.`을 붙인다. `chief.happy`로 넘기면 `animation.chief.happy`가 된다.
- 키프레임 편집은 **애니메이션 모드**에서만 된다. 모드를 바꾸고 애니메이션을 선택한 뒤 편집한다.
- 등록된 형식 목록을 주는 전용 도구가 없다. 프로젝트가 열린 상태에서 `risky_eval`로 `Object.keys(Formats)`를 읽는다(프로젝트가 없으면 실행 도구가 실패한다).
- 프로젝트가 없으면 편집·스크린샷 도구가 꺼져 있고 도구 검색에도 안 나온다. 그때는 `risky_eval`로 한다(대화 몸짓 에셋 때 쓴 방법):
  - 열기: `Blockbench.read([경로], {readtype:'text'}, files => loadModelFile(files[0]))`
  - 애니메이션 만들기: `new Animation({name, loop, length, snapping: 20}).add(false)` → `anim.getBoneAnimator(group).addKeyframe({channel, time, interpolation, data_points: [{x, y, z}]})`,
    `Undo.initEdit`·`finishEdit`로 감싼다.
  - 자세 보기: `anim.select()` → `Timeline.setTime(t)` → `Animator.preview()`, 카메라는 `Preview.selected.camera.position.set(…)` +
    `controls.target.set(…)` + `controls.update()`, 그다음 `capture_app_screenshot`. 모델은 -Z가 앞(얼굴 쪽).
  - 내보내기·저장: `Format.animation_codec.compileFile(Animation.all)`을 탭 들여쓰기 JSON으로, `.bbmodel`은 `Codecs.project.compile()`,
    둘 다 `require('fs').writeFileSync`. 저장 뒤 `Project.saved = true`.

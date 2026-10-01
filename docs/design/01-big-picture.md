# 01 — 큰 그림

2026-10-02 · 정함 · 고치는 비용: 방향이라 문서·코드에 직접 남지는 않지만, 뒤의 모든 주제가 이 위에 선다.

## 한 줄

**사건 → 규칙 → 장부 → 화면.** 화면에서 누른 버튼은 다시 사건이 되어 한 바퀴 돈다.

보드게임으로 말하면: 작가는 **규칙서**(퀘스트·대사·그래프 문서)를 쓰고, 플레이어의 행동(**사건**)이 일어나면 규칙서를 펴 보고,
결과를 **점수판**(장부)에 적고, 플레이어 화면은 점수판을 **비춰 보이기만** 한다.

| 칸 | 무엇 | 예 |
|---|---|---|
| 사건 | 게임에서 일어난 일. 무엇이든 사건이 될 수 있다 | 대화창 버튼, 처치, 줍기, 퀘스트 완료, (나중) 컷신이 끝남 |
| 규칙 | 작가가 쓴 문서 | 퀘스트 = **길**, NPC = **대사**, 그래프 = **세상의 반응**, 변수 목록 = 장부 칸의 **이름표** |
| 장부 | 플레이어(또는 서버)마다 적힌 값 | 퀘스트가 알아서 만드는 칸 + 작가가 목록에 만든 변수 |
| 화면 | 장부의 거울 | J 화면, 토스트, 대화창(대사 조건), (나중) 컷신 재생 |

## 정한 것 (사용자, 2026-10-01~02)

1. **퀘스트 = 방과 문.** 단계는 방, 방에서 나가는 문에는 목표가 있다. **NPC에게 가는 것(건네기·말하기)도 문의 목표 하나**다
   (Narrative의 TalkToNPC, Quest Machine의 노드 조건). 그래서 NPC가 없는 문은 저절로 열리고, "저절로 넘어가는 단계"는 특별한
   규칙이 아니게 된다. 문이 여럿이면 갈림(말로 고르기·한 일·예전 일 모두 같은 모양). 문 여러 개의 실제 칸은 03에서.
2. **그래프 = 퀘스트 밖 세상의 반응.** 퀘스트 흐름을 건드리지 않고 사건을 듣는다(NPC 세우기, 컷신 틀기, 변수 적기, 여러 퀘스트에
   걸친 일). 퀘스트와 그래프의 연결은 사건뿐이다. "이야기의 흐름은 퀘스트에서, 세상의 반응은 그래프에서."
3. **변수(작가가 만드는 장부 칸)는 한 곳에 목록으로**, 쓰는 곳(그래프·대사 조건)에서는 목록에서 고른다(Dialogue System의
   Variables). 이름이 흩어져 조용히 안 맞는 일을 막는다. 지금의 `flag`(그래프만 읽고 씀)가 이 자리로 넓어진다. 칸 모양은 02에서.
4. **대사도 장부를 읽고 쓴다.** 지금 대사 조건은 퀘스트 상태·단계·거절 수만 본다.
5. **파일과 폴더.** 한 항목 = 한 파일, 폴더 = 진짜 폴더, **종류를 섞는 폴더**(이야기 폴더에 퀘스트·NPC·그래프·컷신), 폴더 안 폴더.
   에디터에 유니티·언리얼 콘텐츠 창 같은 **파일 탐색기**. 가리키기는 **파일 안의 id**(유니티 GUID식, 0004 그대로)로 하고 경로로
   가리키지 않는다(데이터팩·언리얼은 이름을 바꾸면 깨지거나 redirector가 남는다). 자세한 것은 06에서.
6. **방향**: 웹 패널에서 쓰고(Typewriter), 게임 안에서는 풍부한 시네마틱(NarrativeCraft). 컷신은 **따로 된 파일**이고, 대사·퀘스트·
   그래프는 이름으로 **부르기만** 한다(유니티 Timeline을 Dialogue System 시퀀서가 부르는 것처럼).

## 추천만 한 것 (아직 안 정함)

- 컷신은 **대사가 부르는 것부터**(`<play=…>` 옆에 컷신 부르기, 짧게 끼워 넣기). 컷신이 대사를 품는 모양(시간 막대 위의 대사)은
  컷신 편집기를 만들 때. 건너뛰기는 그때 묻는다(07).
- 파일을 에디터 밖에서 고쳤을 때 언제 다시 읽나(06).

## 지금 Lorebench와 다른 곳

이미 있는 것: 장부(`RecordStore`, 주인별 키), 사건(처치·수확·번식·대화 버튼·접속), 계산하는 값(다 채움, 기다림 끝), 대화창·타이핑·
글 안 표시(시퀀서 자리), 그래프(`OnNpcInteract`·`OnPlayerJoin`·`Set Flag`…), 폴더 트리. 다른 곳:

- 단계를 넘기는 문이 **[건네기]·[받기] 하나**다(`Quests.complete`, 그래프 `Complete Quest`도 같은 길). → 1
- `flag`는 그래프만 읽고 쓴다(대사 조건은 못 읽음). → 3, 4
- 세 종류의 큰 파일(`quests.json`·`npcs.json`·`graphs.json`) 안에 폴더 목록이 따로 있다(0008). → 5

## 사례

| 사례 | 장부 | 고치는 것 | 보여주기 | NPC에게 가기 |
|---|---|---|---|---|
| Typewriter (마인크래프트) | 사실(fact, 숫자, 적어 두는 것·계산하는 것, 플레이어/월드/전체) | 조각마다 조건·고치기 | 퀘스트는 거울 | 사건 하나 |
| Narrative (언리얼) | 퀘스트 그래프 | 상태(방)·갈래(문)의 과제 | 그래프가 곧 일지 | 과제 하나 |
| Dialogue System (유니티) | 변수 + 퀘스트 상태 | 대사 안의 실행, Condition Observer | 퀘스트 로그 | 대사 안 |
| Quest Machine (유니티) | 퀘스트 카운터 | 노드 조건(메시지를 듣는 카운터) | 노드 상태별 글 | 노드 조건 |

- 커뮤니티: 퀘스트가 흐름을 쥐는 쪽(A)은 읽고 시험하기 쉽고, 장부가 쥐는 쪽(B)은 넓어지기 쉽지만 작가의 장부 정리가 짐이다
  (Emily Short). B인데 편하다는 평을 받는 도구는 도구가 장부 정리를 가려 준다(Typewriter: 웹 패널은 칭찬, 개념 설명은 불만).
  Pixel Crushers는 대화가 많으면 Dialogue System, 게임플레이가 많으면 Quest Machine이 편하다고 한다. Lorebench는 둘 다라
  **속은 장부, 겉은 흐름이 보이는 그림**을 고른다.

출처: [Typewriter Questing](https://docs.typewritermc.com/docs/creating-stories/questing),
[Typewriter Facts](https://docs.typewritermc.com/docs/creating-stories/facts),
[Typewriter 리뷰](https://www.spigotmc.org/resources/typewriter-next-generation-questing.107748/reviews),
[Narrative Quests](https://docs.narrativetools.io/pro/quests/),
[Dialogue System Quests](https://www.pixelcrushers.com/dialogue_system/manual2x/html/quests.html),
[Dialogue System vs Quest Machine](https://www.pixelcrushers.com/dialogue-system/dialogue-system-quest-machine-comparison/),
[Quest Machine 설명서](https://www.pixelcrushers.com/quest_machine/Quest_Machine_Manual.pdf),
[Dialogue System Cutscene Sequences](https://www.pixelcrushers.com/dialogue_system/manual2x/html/cutscene_sequences.html),
[Unity Cinemachine and Timeline](https://docs.unity3d.com/Packages/com.unity.cinemachine@2.10/manual/CinemachineTimeline.html),
[Emily Short, Beyond Branching](https://emshort.blog/2016/04/12/beyond-branching-quality-based-and-salience-based-narrative-structures/),
[NarrativeCraft](https://github.com/NarrativeCraft/NarrativeCraft).

# EgoFlow Rebuild Plan

## Summary

이번 개편은 기존 `samples/CameraAccessAndroid`, `samples/video-ingest-server`를 직접 수정하는 방식이 아니라, `samples` 아래에 새로운 프로젝트 폴더를 추가하는 방식으로 진행한다. 기존 샘플은 참조용으로 유지하고, 새 기본 플로우는 `Glasses -> Phone import -> Android upload queue -> HTTP upload API -> local cleanup`으로 전환한다.

1차 범위는 `Android + Server`다. History는 앱 로컬 캐시를 기준으로 관리하고, 서버는 최소 업로드 API만 제공한다.

## Repository Changes

- 기존 `samples/CameraAccessAndroid`는 수정하지 않는다.
- 기존 `samples/video-ingest-server`도 수정하지 않는다.
- 새 Android 프로젝트를 `samples` 하위에 별도 폴더로 생성한다.
  - 예시 이름: `samples/glasses-upload-android`
- 새 서버 프로젝트를 `samples` 하위에 별도 폴더로 생성한다.
  - 예시 이름: `samples/video-upload-server`
- 루트 문서에는 기존 샘플이 legacy/reference 용도이고, 새 프로젝트가 실제 개편 대상이라는 점을 명시한다.
- 기존 RTMP/MediaMTX 경로는 1차 구현 범위 밖으로 두고, 후속 정리 전까지는 reference 상태로 남긴다.

## Android App Plan

- 앱의 역할을 `live preview/RTMP publisher`가 아니라 `glasses connection + import watcher + upload manager + history UI`로 정의한다.
- 메인 화면은 연결 상태 중심으로 재구성한다.
  - 연결 안 됨: Meta AI glasses 연결 가이드, `Connect My Glasses` 진입, Auto Import 활성화 안내
  - 연결됨: 모델명, 등록 상태, active device 여부 등 DAT SDK 상태 표시
- 새 앱 설정 항목:
  - `serverBaseUrl`
  - `importDirectory`
  - `deleteAfterUpload`
- 업로드 파이프라인:
  - 사용자가 지정한 import folder 감시
  - 새 비디오만 큐에 등록
  - 상태: `Waiting`, `Uploading`, `Succeeded`, `Failed(reason)`
  - 성공 시 서버 응답 확인 후 폰 로컬 파일 삭제
  - 실패 시 파일 유지, 오류 사유 기록
- History 화면:
  - 최신순 목록
  - 무한 스크롤
  - 로컬 영속 저장소 기반 캐시
  - row 필드: 제목, 길이, 상태, 실패 사유
- 내부 데이터 모델:
  - `UploadRecord`: local id, source uri/path, title, duration, discoveredAt, status, errorMessage, uploadedAt
  - `UploadStatus`: waiting/uploading/succeeded/failed
- 백그라운드 업로드는 Android 제약에 맞는 worker/foreground-compatible 구조로 설계한다.
- 기존 `CameraAccessAndroid`의 RTMP/Gemini/OpenClaw 구조는 새 앱으로 가져오지 않는다. 필요한 DAT 연결 로직만 참조해 별도 구현한다.

## Server Plan

- 새 서버는 `samples/video-upload-server` 같은 별도 폴더에 만든다.
- 기존 `video-ingest-server`의 MediaMTX 구성은 유지하되 개편 대상에서 제외한다.
- 1차 서버 공용 API:
  - `POST /api/videos`
- request:
  - multipart/form-data
  - 필수 파일 필드 1개
  - 선택 metadata 필드 허용
- response:
  - 성공: `{ "message": "success" }`
  - 실패: `{ "message": "<reason>" }`
- 서버 책임:
  - 파일 저장
  - 파일명 충돌 방지
  - 기본 요청 검증
  - 오류 메시지 반환
- 서버 history 조회 API는 1차에서 만들지 않는다.

## Test Plan

- Android 연결 UI
  - 미등록 상태에서 연결 가이드와 등록 버튼 표시
  - 등록 후 기기 정보/상태 표시
- import detection
  - 기존 파일은 업로드하지 않음
  - 새 비디오 추가 시 1회만 큐 등록
  - 동일 파일 재감지 시 중복 레코드 방지
- upload queue
  - 성공 시 `Succeeded` 전환 및 로컬 파일 삭제
  - 실패 시 `Failed(...)` 기록 및 파일 유지
  - 앱 재시작 후 queue/history 복원
- History UI
  - 최신순 정렬
  - 무한 스크롤
  - 실패 사유 표시
- Server API
  - 정상 업로드 시 `200` + `{"message":"success"}`
  - 잘못된 요청/저장 실패 시 실패 메시지 반환
- integration
  - 테스트용 import folder에 파일을 넣어 end-to-end 검증 가능해야 함

## Assumptions and Defaults

- 1차 범위는 Android와 서버만 포함한다.
- Meta glasses 내부 저장소 삭제는 SDK 제약상 직접 제어하지 못한다고 가정한다.
- 1차의 자동 삭제는 폰에 import된 로컬 파일 삭제만 의미한다.
- Meta AI auto import의 실제 저장 위치 불확실성을 피하기 위해, 1차는 사용자가 지정한 import folder 기반으로 동작한다.
- History의 단일 소스는 로컬 DB다.
- 기존 `CameraAccessAndroid`, `video-ingest-server`는 직접 편집하지 않고 reference로만 유지한다.
- 최종 산출 문서는 새 프로젝트 쪽에 별도 Markdown 문서로 두는 것을 기본값으로 한다.
  - 예시 경로: `samples/glasses-upload-android/PLAN.md` 또는 `samples/PLAN-egoflow-rebuild.md`

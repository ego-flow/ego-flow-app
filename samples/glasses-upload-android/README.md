# EgoFlow Glasses Upload Android

이 프로젝트는 `samples/CameraAccessAndroid`를 대체하지 않는다. 기존 샘플은 legacy/reference 용도로 유지하고, 이 앱이 1차 개편 범위인 `glasses -> phone import -> upload queue -> HTTP upload API -> local cleanup` 플로우를 담당한다.

## 핵심 기능

- Meta DAT 기반 안경 등록 상태 표시
- 사용자가 지정한 import directory 감시
- 새 비디오만 로컬 DB에 큐 등록
- WorkManager 기반 업로드 실행
- 성공 시 로컬 파일 삭제 옵션
- 로컬 history 캐시와 실패 사유 표시

## 설정

Meta DAT Android 패키지는 GitHub Packages에서 내려받으므로 아래 중 하나가 필요하다.

```bash
export GITHUB_TOKEN=YOUR_GITHUB_CLASSIC_PAT
```

또는 `local.properties`에:

```properties
github_token=YOUR_GITHUB_CLASSIC_PAT
```

## 실행

```bash
cd samples/glasses-upload-android
./gradlew assembleDebug
```

기본 서버 URL은 `http://10.0.2.2:8000`이고, 기본 import directory는 `/storage/emulated/0/Movies/EgoFlowImports`다.

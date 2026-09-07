# admin/CLAUDE.md

`admin/` 디렉터리는 React + TypeScript 기반 운영 모니터링 대시보드(SPA)입니다. Spring Boot 백엔드와 별개로 동작합니다. 공통 규칙은 루트 [CLAUDE.md](../CLAUDE.md)를 참조하세요.

**Tech Stack**: React 19, TypeScript, Vite, Tailwind CSS, React Query, React Router, Recharts, Zustand

**Pages**: Dashboard (KPI·경고 카드·주간 추이 차트), Members, Families, Albums, Payments, Notifications, Logs, DevTools (개발 환경 전용)

**인증**: JWT를 `localStorage`(`admin_token` 키)에 저장하고 authStore(Zustand)로 노출, PrivateRoute로 보호. 백엔드 `AdminAuthController`(`/api/v1/auth/admin/login`) 연동, refresh는 `/api/v1/auth/admin/refresh`.

**Dev**:
```bash
cd admin
npm install
npm run dev   # localhost:5173
npm run build # dist/ 생성
```

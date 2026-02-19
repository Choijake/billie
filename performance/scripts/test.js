import { sleep } from 'k6';
import { htmlReport } from "https://raw.githubusercontent.com/benc-uk/k6-reporter/main/dist/bundle.js";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.1/index.js";

export const options = {
    vus: 1,
    iterations: 1,
};

export default function () {
    console.log("시스템 연결 상태 점검 중...");
    sleep(1);
}

export function handleSummary(data) {
    return {
        "k6-report.html": htmlReport(data),
        stdout: textSummary(data, { indent: " ", enableColors: true }),
    };
}
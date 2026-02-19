package com.analyzer

import java.io.File

class HtmlReportGenerator {

    fun generate(
        records: List<TestRecord>,
        repoPath: String,
        outputPath: String,
        systemNames: Map<String, String> = emptyMap(),
        authorNames: Map<String, String> = emptyMap()
    ) {
        val jsonData = serializeToJson(records)
        val systemNamesJson = serializeMapToJson(systemNames)
        val authorNamesJson = serializeMapToJson(authorNames)
        val html = buildHtml(jsonData, systemNamesJson, authorNamesJson, repoPath)
        File(outputPath).writeText(html, Charsets.UTF_8)
    }

    private fun serializeToJson(records: List<TestRecord>): String {
        if (records.isEmpty()) return "[]"
        val sb = StringBuilder()
        sb.append("[\n")
        for ((index, record) in records.withIndex()) {
            sb.append("{")
            sb.append("\"author\":\"${escapeJson(record.authorEmail)}\",")
            sb.append("\"test\":\"${escapeJson(record.functionName)}\",")
            sb.append("\"file\":\"${escapeJson(record.filePath)}\",")
            sb.append("\"date\":\"${escapeJson(record.date)}\",")
            if (record.systemId != null) {
                sb.append("\"system\":\"${escapeJson(record.systemId)}\"")
            } else {
                sb.append("\"system\":null")
            }
            sb.append("}")
            if (index < records.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun serializeMapToJson(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        val entries = map.entries.joinToString(",") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        return "{$entries}"
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("/", "\\/")
    }

    private fun buildHtml(
        jsonData: String,
        systemNamesJson: String,
        authorNamesJson: String,
        repoPath: String
    ): String {
        return """<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Отчёт по автоматизации тестов</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.js"></script>
${buildCss()}
</head>
<body>

<div class="container">
    <h1>Отчёт по автоматизации тестов</h1>
    <p class="repo-path">Репозиторий: <code>${escapeHtml(repoPath)}</code></p>

    <div class="period-selector">
        <span class="period-label">Период:</span>
        <button class="period-btn active" data-period="week">Последняя неделя</button>
        <button class="period-btn" data-period="month">Последний месяц</button>
        <button class="period-btn" data-period="quarter">Квартал</button>
        <button class="period-btn" data-period="year">Последний год</button>
        <button class="period-btn" data-period="custom">Конкретный месяц</button>
        <button class="period-btn" data-period="all">Всё время</button>
        <div class="custom-period" id="customPeriod" style="display:none">
            <select id="customMonth">
                <option value="1">Январь</option>
                <option value="2">Февраль</option>
                <option value="3">Март</option>
                <option value="4">Апрель</option>
                <option value="5">Май</option>
                <option value="6">Июнь</option>
                <option value="7">Июль</option>
                <option value="8">Август</option>
                <option value="9">Сентябрь</option>
                <option value="10">Октябрь</option>
                <option value="11">Ноябрь</option>
                <option value="12">Декабрь</option>
            </select>
            <select id="customYear"></select>
            <button class="apply-btn" id="applyCustom">Показать</button>
        </div>
        <div class="custom-period" id="quarterPeriod" style="display:none">
            <select id="quarterSelect">
                <option value="1">Q1 (Янв–Мар)</option>
                <option value="2">Q2 (Апр–Июн)</option>
                <option value="3">Q3 (Июл–Сен)</option>
                <option value="4">Q4 (Окт–Дек)</option>
            </select>
            <select id="quarterYear"></select>
            <button class="apply-btn" id="applyQuarter">Показать</button>
        </div>
        <div class="system-filter">
            <span class="period-label">Система:</span>
            <select id="systemFilter">
                <option value="all">Все системы</option>
            </select>
        </div>
    </div>

    <div class="summary-section">
        <h2>Сводка</h2>
        <table id="summaryTable">
            <thead>
                <tr>
                    <th>Автор</th>
                    <th>Количество тестов</th>
                    <th>% от общего</th>
                </tr>
            </thead>
            <tbody id="summaryBody"></tbody>
            <tfoot>
                <tr class="total-row">
                    <td><strong>Всего</strong></td>
                    <td id="totalCount"><strong>0</strong></td>
                    <td><strong>100%</strong></td>
                </tr>
            </tfoot>
        </table>
        <p class="no-data" id="noData" style="display:none">Нет данных за выбранный период.</p>
    </div>

    <div class="charts-section">
        <h2>Тесты по авторам</h2>
        <div class="chart-container">
            <canvas id="authorChart"></canvas>
        </div>

        <h2>Динамика по времени</h2>
        <div class="chart-container chart-timeline">
            <canvas id="timelineChart"></canvas>
        </div>
    </div>

    <div class="systems-section">
        <h2>Тесты по системам</h2>
        <table id="systemsTable" style="display:none">
            <thead>
                <tr>
                    <th>Система</th>
                    <th>Количество тестов</th>
                    <th>Авторы</th>
                </tr>
            </thead>
            <tbody id="systemsBody"></tbody>
        </table>
        <p class="no-data" id="noSystemData" style="display:none">Нет данных по системам за выбранный период.</p>

        <h2>Количество тестов по системам</h2>
        <div class="chart-container">
            <canvas id="systemCountChart"></canvas>
        </div>

        <h2>Авторы × Системы</h2>
        <div class="chart-container">
            <canvas id="systemChart"></canvas>
        </div>
    </div>

    <div class="details-section">
        <h2>Подробности</h2>
        <div id="detailsList"></div>
    </div>
</div>

<script>
const DATA = $jsonData;
const SYSTEM_NAMES = $systemNamesJson;
const AUTHOR_NAMES = $authorNamesJson;
${buildJavaScript()}
</script>

</body>
</html>"""
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun buildCss(): String {
        return """<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: #f5f7fa;
    color: #1a1a2e;
    line-height: 1.6;
}
.container {
    max-width: 1100px;
    margin: 0 auto;
    padding: 32px 24px;
}
h1 {
    font-size: 28px;
    font-weight: 700;
    margin-bottom: 8px;
}
h2 {
    font-size: 20px;
    font-weight: 600;
    margin: 32px 0 16px;
}
.repo-path {
    color: #555;
    margin-bottom: 24px;
}
.repo-path code {
    background: #e8ecf1;
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 14px;
}
.period-selector {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-bottom: 24px;
    padding: 16px;
    background: #fff;
    border-radius: 8px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.period-label {
    font-weight: 600;
    margin-right: 8px;
}
.period-btn {
    padding: 8px 16px;
    border: 1px solid #d0d5dd;
    border-radius: 6px;
    background: #fff;
    cursor: pointer;
    font-size: 14px;
    transition: all 0.15s;
}
.period-btn:hover { background: #f0f2f5; }
.period-btn.active {
    background: #2563eb;
    color: #fff;
    border-color: #2563eb;
}
.custom-period {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-left: 8px;
}
.custom-period select, .apply-btn {
    padding: 8px 12px;
    border: 1px solid #d0d5dd;
    border-radius: 6px;
    font-size: 14px;
    background: #fff;
}
.apply-btn {
    background: #2563eb;
    color: #fff;
    border-color: #2563eb;
    cursor: pointer;
}
.apply-btn:hover { background: #1d4ed8; }
.system-filter {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-left: auto;
}
.system-filter select {
    padding: 8px 12px;
    border: 1px solid #d0d5dd;
    border-radius: 6px;
    font-size: 14px;
    background: #fff;
}
table {
    width: 100%;
    border-collapse: collapse;
    background: #fff;
    border-radius: 8px;
    overflow: hidden;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
thead th {
    background: #f8f9fb;
    padding: 12px 16px;
    text-align: left;
    font-weight: 600;
    font-size: 14px;
    border-bottom: 2px solid #e5e7eb;
}
tbody td, tfoot td {
    padding: 10px 16px;
    border-bottom: 1px solid #f0f0f0;
    font-size: 14px;
}
tbody tr:hover { background: #f8f9fb; }
.total-row td {
    background: #f0f4ff;
    border-top: 2px solid #2563eb;
    border-bottom: none;
}
.no-data {
    padding: 24px;
    text-align: center;
    color: #888;
    font-style: italic;
}
.chart-container {
    background: #fff;
    border-radius: 8px;
    padding: 24px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
    margin-bottom: 16px;
}
.chart-timeline { min-height: 300px; }
.details-section details {
    background: #fff;
    border-radius: 8px;
    margin-bottom: 8px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
    overflow: hidden;
}
.details-section summary {
    padding: 12px 16px;
    cursor: pointer;
    font-weight: 600;
    font-size: 14px;
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.details-section summary:hover { background: #f8f9fb; }
.details-section summary .count {
    background: #2563eb;
    color: #fff;
    padding: 2px 10px;
    border-radius: 12px;
    font-size: 13px;
    font-weight: 500;
}
.details-section ul {
    list-style: none;
    padding: 0 16px 12px;
}
.details-section li {
    padding: 4px 0;
    font-size: 13px;
    color: #444;
    border-bottom: 1px solid #f5f5f5;
}
.details-section li:last-child { border-bottom: none; }
.details-section .test-name { font-weight: 500; color: #1a1a2e; }
.details-section .file-path { color: #888; margin-left: 8px; font-size: 12px; }
.system-badge {
    display: inline-block;
    background: #dbeafe;
    color: #1e40af;
    padding: 1px 8px;
    border-radius: 4px;
    font-size: 11px;
    font-weight: 500;
    margin-left: 8px;
}
</style>"""
    }

    private fun buildJavaScript(): String {
        return """
const COLORS = [
    '#2563eb','#e11d48','#16a34a','#ea580c','#7c3aed',
    '#0891b2','#c026d3','#ca8a04','#dc2626','#059669',
    '#4f46e5','#d97706','#0d9488','#be185d','#65a30d'
];

let authorChart = null;
let timelineChart = null;
let systemChart = null;
let systemCountChart = null;
let currentPeriod = 'week';

function resolveAuthor(email) {
    return AUTHOR_NAMES[email] || email;
}

function resolveSystem(id) {
    if (!id) return id;
    return SYSTEM_NAMES[id] || id;
}

function resolveSystemLabel(id) {
    if (!id) return '\u041d\u0435 \u0443\u043a\u0430\u0437\u0430\u043d\u0430';
    return SYSTEM_NAMES[id] ? SYSTEM_NAMES[id] + ' (' + id + ')' : id;
}

function parseDate(iso) { return new Date(iso); }

function filterByPeriod(records, periodType, cMonth, cYear, cQuarter) {
    if (periodType === 'all') return records;
    const now = new Date();
    let start, end;
    switch (periodType) {
        case 'week':
            start = new Date(now); start.setDate(now.getDate() - 7);
            end = now;
            break;
        case 'month':
            start = new Date(now); start.setMonth(now.getMonth() - 1);
            end = now;
            break;
        case 'year':
            start = new Date(now); start.setFullYear(now.getFullYear() - 1);
            end = now;
            break;
        case 'quarter':
            const qStartMonth = (cQuarter - 1) * 3;
            start = new Date(cYear, qStartMonth, 1);
            end = new Date(cYear, qStartMonth + 3, 0, 23, 59, 59, 999);
            break;
        case 'custom':
            start = new Date(cYear, cMonth - 1, 1);
            end = new Date(cYear, cMonth, 0, 23, 59, 59, 999);
            break;
    }
    return records.filter(r => {
        const d = parseDate(r.date);
        return d >= start && d <= end;
    });
}

function mergeByAuthor(records) {
    return records.map(r => ({
        ...r,
        author: resolveAuthor(r.author)
    }));
}

function aggregateByAuthor(filtered) {
    const merged = mergeByAuthor(filtered);
    const map = {};
    merged.forEach(r => {
        if (!map[r.author]) map[r.author] = [];
        map[r.author].push(r);
    });
    return Object.entries(map)
        .sort((a, b) => b[1].length - a[1].length)
        .reduce((obj, [k, v]) => { obj[k] = v; return obj; }, {});
}

function aggregateBySystem(filtered) {
    const merged = mergeByAuthor(filtered);
    const map = {};
    merged.forEach(r => {
        const sys = resolveSystemLabel(r.system);
        if (!map[sys]) map[sys] = [];
        map[sys].push(r);
    });
    return Object.entries(map)
        .sort((a, b) => b[1].length - a[1].length)
        .reduce((obj, [k, v]) => { obj[k] = v; return obj; }, {});
}

function getTimeBuckets(filtered, periodType) {
    if (filtered.length === 0) return { labels: [], datasets: {} };
    const merged = mergeByAuthor(filtered);
    const bucketMap = {};
    const authorSet = new Set();

    merged.forEach(r => {
        const d = parseDate(r.date);
        let key;
        if (periodType === 'week') {
            key = d.toISOString().slice(0, 10);
        } else if (periodType === 'month' || periodType === 'custom') {
            const weekStart = new Date(d);
            const day = d.getDay() || 7;
            weekStart.setDate(d.getDate() - day + 1);
            key = weekStart.toISOString().slice(0, 10);
        } else {
            key = d.toISOString().slice(0, 7);
        }
        if (!bucketMap[key]) bucketMap[key] = {};
        if (!bucketMap[key][r.author]) bucketMap[key][r.author] = 0;
        bucketMap[key][r.author]++;
        authorSet.add(r.author);
    });

    const labels = Object.keys(bucketMap).sort();
    const authors = [...authorSet];
    const datasets = {};
    authors.forEach(a => {
        datasets[a] = labels.map(l => bucketMap[l][a] || 0);
    });
    return { labels, datasets, authors };
}

const MONTH_NAMES = [
    '\u042f\u043d\u0432','\u0424\u0435\u0432','\u041c\u0430\u0440','\u0410\u043f\u0440','\u041c\u0430\u0439','\u0418\u044e\u043d',
    '\u0418\u044e\u043b','\u0410\u0432\u0433','\u0421\u0435\u043d','\u041e\u043a\u0442','\u041d\u043e\u044f','\u0414\u0435\u043a'
];

function formatLabel(key, periodType) {
    if (periodType === 'year' || periodType === 'quarter') {
        const parts = key.split('-');
        return MONTH_NAMES[parseInt(parts[1]) - 1] + ' ' + parts[0];
    }
    const parts = key.split('-');
    return parseInt(parts[2]) + ' ' + MONTH_NAMES[parseInt(parts[1]) - 1];
}

function renderTable(byAuthor) {
    const body = document.getElementById('summaryBody');
    const totalEl = document.getElementById('totalCount');
    const noData = document.getElementById('noData');
    const table = document.getElementById('summaryTable');

    const entries = Object.entries(byAuthor);
    const total = entries.reduce((s, e) => s + e[1].length, 0);

    if (entries.length === 0) {
        table.style.display = 'none';
        noData.style.display = 'block';
        return;
    }
    table.style.display = '';
    noData.style.display = 'none';

    body.innerHTML = entries.map(([author, tests]) => {
        const pct = total > 0 ? (tests.length / total * 100).toFixed(1) : '0.0';
        return '<tr><td>' + escapeHtml(author) + '</td><td>' + tests.length + '</td><td>' + pct + '%</td></tr>';
    }).join('');

    totalEl.innerHTML = '<strong>' + total + '</strong>';
}

function renderAuthorChart(byAuthor) {
    const ctx = document.getElementById('authorChart').getContext('2d');
    if (authorChart) authorChart.destroy();

    const entries = Object.entries(byAuthor);
    const labels = entries.map(e => e[0]);
    const data = entries.map(e => e[1].length);
    const colors = labels.map((_, i) => COLORS[i % COLORS.length]);

    authorChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{
                label: '\u041a\u043e\u043b\u0438\u0447\u0435\u0441\u0442\u0432\u043e \u0442\u0435\u0441\u0442\u043e\u0432',
                data: data,
                backgroundColor: colors,
                borderRadius: 4
            }]
        },
        options: {
            indexAxis: 'y',
            responsive: true,
            plugins: { legend: { display: false } },
            scales: { x: { beginAtZero: true, ticks: { precision: 0 } } }
        }
    });
}

function renderTimelineChart(buckets, periodType) {
    const ctx = document.getElementById('timelineChart').getContext('2d');
    if (timelineChart) timelineChart.destroy();

    if (!buckets.authors || buckets.authors.length === 0) {
        timelineChart = new Chart(ctx, {
            type: 'bar', data: { labels: [], datasets: [] }, options: { responsive: true }
        });
        return;
    }

    const labels = buckets.labels.map(l => formatLabel(l, periodType));
    const datasets = buckets.authors.map((author, i) => ({
        label: author,
        data: buckets.datasets[author],
        backgroundColor: COLORS[i % COLORS.length] + '99',
        borderColor: COLORS[i % COLORS.length],
        borderWidth: 2, fill: false, tension: 0.3
    }));

    timelineChart = new Chart(ctx, {
        type: 'line',
        data: { labels: labels, datasets: datasets },
        options: {
            responsive: true,
            interaction: { mode: 'index', intersect: false },
            plugins: { legend: { position: 'bottom' } },
            scales: { y: { beginAtZero: true, ticks: { precision: 0 } } }
        }
    });
}

function renderSystemsTable(bySystem) {
    const body = document.getElementById('systemsBody');
    const table = document.getElementById('systemsTable');
    const noData = document.getElementById('noSystemData');

    const OTHER_KEY = '\u041d\u0435 \u0443\u043a\u0430\u0437\u0430\u043d\u0430';
    const known = Object.entries(bySystem).filter(([sys]) => sys !== OTHER_KEY);
    const other = bySystem[OTHER_KEY];

    // Все записи: сначала известные системы, в конце — «Другое»
    const entries = other ? [...known, ['\u0414\u0440\u0443\u0433\u043e\u0435', other]] : known;

    if (entries.length === 0) {
        table.style.display = 'none';
        noData.style.display = 'block';
        return;
    }
    table.style.display = '';
    noData.style.display = 'none';

    body.innerHTML = entries.map(([system, tests]) => {
        const isOther = system === '\u0414\u0440\u0443\u0433\u043e\u0435';
        const authorCounts = {};
        tests.forEach(t => { authorCounts[t.author] = (authorCounts[t.author] || 0) + 1; });
        const authorSummary = Object.entries(authorCounts)
            .sort((a, b) => b[1] - a[1])
            .map(([a, c]) => escapeHtml(a) + ' (' + c + ')')
            .join(', ');
        const cell = isOther
            ? '<td><em>\u0414\u0440\u0443\u0433\u043e\u0435</em></td>'
            : '<td><code>' + escapeHtml(system) + '</code></td>';
        return '<tr>' + cell + '<td>' + tests.length + '</td><td>' + authorSummary + '</td></tr>';
    }).join('');
}

function renderSystemCountChart(bySystem) {
    const ctx = document.getElementById('systemCountChart').getContext('2d');
    if (systemCountChart) systemCountChart.destroy();

    const OTHER_KEY = '\u041d\u0435 \u0443\u043a\u0430\u0437\u0430\u043d\u0430';
    const known = Object.entries(bySystem).filter(([sys]) => sys !== OTHER_KEY);
    const other = bySystem[OTHER_KEY];
    const entries = other ? [...known, ['\u0414\u0440\u0443\u0433\u043e\u0435', other]] : known;

    if (entries.length === 0) {
        systemCountChart = new Chart(ctx, {
            type: 'bar', data: { labels: [], datasets: [] }, options: { responsive: true }
        });
        return;
    }

    const labels = entries.map(e => e[0]);
    const data = entries.map(e => e[1].length);
    const colors = labels.map((_, i) => COLORS[i % COLORS.length]);

    systemCountChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [{
                label: '\u041a\u043e\u043b\u0438\u0447\u0435\u0441\u0442\u0432\u043e \u0442\u0435\u0441\u0442\u043e\u0432',
                data: data,
                backgroundColor: colors,
                borderRadius: 4
            }]
        },
        options: {
            indexAxis: 'y',
            responsive: true,
            plugins: { legend: { display: false } },
            scales: { x: { beginAtZero: true, ticks: { precision: 0 } } }
        }
    });
}

function renderSystemChart(bySystem) {
    const ctx = document.getElementById('systemChart').getContext('2d');
    if (systemChart) systemChart.destroy();

    const OTHER_KEY = '\u041d\u0435 \u0443\u043a\u0430\u0437\u0430\u043d\u0430';
    const known = Object.entries(bySystem).filter(([sys]) => sys !== OTHER_KEY);
    const other = bySystem[OTHER_KEY];
    const entries = other ? [...known, ['\u0414\u0440\u0443\u0433\u043e\u0435', other]] : known;

    if (entries.length === 0) {
        systemChart = new Chart(ctx, {
            type: 'bar', data: { labels: [], datasets: [] }, options: { responsive: true }
        });
        return;
    }

    const systems = entries.map(e => e[0]);
    const authorSet = new Set();
    entries.forEach(([, tests]) => tests.forEach(t => authorSet.add(t.author)));
    const authors = [...authorSet];

    const datasets = authors.map((author, i) => ({
        label: author,
        data: systems.map(sys => {
            const entry = entries.find(([s]) => s === sys);
            return entry ? entry[1].filter(t => t.author === author).length : 0;
        }),
        backgroundColor: COLORS[i % COLORS.length],
        borderRadius: 4
    }));

    systemChart = new Chart(ctx, {
        type: 'bar',
        data: { labels: systems, datasets: datasets },
        options: {
            responsive: true,
            plugins: { legend: { position: 'bottom' } },
            scales: {
                x: { stacked: true },
                y: { stacked: true, beginAtZero: true, ticks: { precision: 0 } }
            }
        }
    });
}

function renderDetails(byAuthor) {
    const container = document.getElementById('detailsList');
    if (Object.keys(byAuthor).length === 0) {
        container.innerHTML = '';
        return;
    }

    container.innerHTML = Object.entries(byAuthor).map(([author, tests]) => {
        const items = tests.map(t => {
            const sysLabel = resolveSystemLabel(t.system);
            const sysBadge = t.system
                ? '<span class="system-badge">' + escapeHtml(sysLabel) + '</span>'
                : '';
            return '<li><span class="test-name">' + escapeHtml(t.test) + '</span>' +
                sysBadge +
                '<span class="file-path">' + escapeHtml(t.file) + '</span></li>';
        }).join('');
        return '<details><summary>' + escapeHtml(author) +
            '<span class="count">' + tests.length + '</span></summary>' +
            '<ul>' + items + '</ul></details>';
    }).join('');
}

function escapeHtml(str) {
    return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function populateSystemFilter(records) {
    const select = document.getElementById('systemFilter');
    const systems = new Set();
    records.forEach(r => { if (r.system) systems.add(r.system); });
    const sorted = [...systems].sort();
    select.innerHTML = '<option value="all">\u0412\u0441\u0435 \u0441\u0438\u0441\u0442\u0435\u043c\u044b</option>' +
        sorted.map(s => {
            const label = resolveSystem(s);
            return '<option value="' + escapeHtml(s) + '">' + escapeHtml(label) + '</option>';
        }).join('');
}

function updateReport(periodType) {
    currentPeriod = periodType;
    const cMonth = parseInt(document.getElementById('customMonth').value);
    const cYear = parseInt(document.getElementById('customYear').value);
    const cQuarter = parseInt(document.getElementById('quarterSelect').value);
    const qYear = parseInt(document.getElementById('quarterYear').value);
    const effectiveYear = periodType === 'quarter' ? qYear : cYear;
    let filtered = filterByPeriod(DATA, periodType, cMonth, effectiveYear, cQuarter);

    const systemFilter = document.getElementById('systemFilter').value;
    if (systemFilter !== 'all') {
        filtered = filtered.filter(r => r.system === systemFilter);
    }

    const byAuthor = aggregateByAuthor(filtered);
    const buckets = getTimeBuckets(filtered, periodType);
    const bySystem = aggregateBySystem(filtered);

    renderTable(byAuthor);
    renderDetails(byAuthor);
    renderSystemsTable(bySystem);

    if (typeof Chart !== 'undefined') {
        renderAuthorChart(byAuthor);
        renderTimelineChart(buckets, periodType);
        renderSystemCountChart(bySystem);
        renderSystemChart(bySystem);
    }
}

// --- \u0418\u043d\u0438\u0446\u0438\u0430\u043b\u0438\u0437\u0430\u0446\u0438\u044f ---
(function init() {
    const yearSelect = document.getElementById('customYear');
    const quarterYearSelect = document.getElementById('quarterYear');
    if (DATA.length > 0) {
        const years = new Set(DATA.map(r => new Date(r.date).getFullYear()));
        const sortedYears = [...years].sort((a, b) => b - a);
        const opts = sortedYears.map(y => '<option value="' + y + '">' + y + '</option>').join('');
        yearSelect.innerHTML = opts;
        quarterYearSelect.innerHTML = opts;
    } else {
        const y = new Date().getFullYear();
        const opt = '<option value="' + y + '">' + y + '</option>';
        yearSelect.innerHTML = opt;
        quarterYearSelect.innerHTML = opt;
    }

    populateSystemFilter(DATA);

    document.getElementById('systemFilter').addEventListener('change', () => {
        updateReport(currentPeriod);
    });

    document.querySelectorAll('.period-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.period-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            const period = btn.dataset.period;
            document.getElementById('customPeriod').style.display =
                period === 'custom' ? 'flex' : 'none';
            document.getElementById('quarterPeriod').style.display =
                period === 'quarter' ? 'flex' : 'none';
            if (period !== 'custom' && period !== 'quarter') updateReport(period);
        });
    });

    document.getElementById('applyCustom').addEventListener('click', () => {
        updateReport('custom');
    });

    document.getElementById('applyQuarter').addEventListener('click', () => {
        updateReport('quarter');
    });

    updateReport('week');
})();
"""
    }
}

// ── Configuration ─────────────────────────────────────────────────────────────
const API_URL = '/api/pipeline';

// Chart Instances (for destruction before re-render)
let forecastChartInstance = null;
let allocationChartInstance = null;

// Color Palette for Clusters
const clusterColors = [
    { border: '#3b82f6', bg: 'rgba(59, 130, 246, 0.1)' }, // Blue
    { border: '#8b5cf6', bg: 'rgba(139, 92, 246, 0.1)' },  // Purple
    { border: '#ec4899', bg: 'rgba(236, 72, 153, 0.1)' },  // Pink
    { border: '#10b981', bg: 'rgba(16, 185, 129, 0.1)' },  // Green
    { border: '#f59e0b', bg: 'rgba(245, 158, 11, 0.1)' },  // Yellow
    { border: '#ef4444', bg: 'rgba(239, 68, 68, 0.1)' }    // Red
];

// ── DOM Elements ──────────────────────────────────────────────────────────────
const dom = {
    refreshBtn: document.getElementById('refreshBtn'),
    loadingOverlay: document.getElementById('loadingOverlay'),
    errorState: document.getElementById('errorState'),
    errorMsg: document.getElementById('errorMsg'),
    dashboardData: document.getElementById('dashboardData'),

    // KPIs
    kpiBudget: document.getElementById('kpiBudget'),
    kpiCost: document.getElementById('kpiCost'),
    kpiCoverage: document.getElementById('kpiCoverage'),
    kpiFeasible: document.getElementById('kpiFeasible'),

    // Tables
    clusterTableBody: document.querySelector('#clusterTable tbody'),
    accuracyTableBody: document.querySelector('#accuracyTable tbody'),

    // Server Info
    serverStatus: document.getElementById('serverStatus'),
    statusText: document.querySelector('.status-text'),
    budgetInput: document.getElementById('budgetInput'),
    footerStatus: document.getElementById('footerStatus'),
    footerUptime: document.getElementById('footerUptime'),
    footerLastRun: document.getElementById('footerLastRun'),

    // Status Tab Elements
    statusTab: document.getElementById('statusTab')
};

// ── Event Listeners ───────────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    fetchHealth();
    fetchData(); // Initial load (uses cache if available)
    fetchSystemStatus(); // First status check

    // Polling
    setInterval(fetchHealth, 10000);
    setInterval(fetchSystemStatus, 15000); // Poll system status every 15s
});

dom.refreshBtn.addEventListener('click', () => {
    const budget = dom.budgetInput.value;
    fetchData(true, budget); // Force run with new config
});

// ── Data Fetching ─────────────────────────────────────────────────────────────

async function fetchHealth() {
    try {
        const res = await fetch('/api/health');
        if (!res.ok) throw new Error();
        const { data } = await res.json();

        dom.serverStatus.className = 'status-indicator online';
        dom.statusText.textContent = 'Engine Online';
        dom.footerStatus.textContent = 'Connected';
        dom.footerStatus.style.color = '#10b981';
        dom.footerUptime.textContent = data.uptime;
    } catch (e) {
        dom.serverStatus.className = 'status-indicator offline';
        dom.statusText.textContent = 'Engine Offline';
        dom.footerStatus.textContent = 'Disconnected';
        dom.footerStatus.style.color = '#ef4444';
        dom.footerUptime.textContent = '--';
    }
}

async function fetchSystemStatus() {
    // Only run if status tab is visible to save resources, or run once on load
    const activeTab = document.querySelector('.tab-content.active');
    const isStatusTab = activeTab && activeTab.id === 'statusTab';

    const endpoints = [
        { id: 'health', url: '/api/health' },
        { id: 'pipeline', url: '/api/pipeline' },
        { id: 'config', url: '/api/config' }
    ];

    endpoints.forEach(async (ep) => {
        const t0 = performance.now();
        const badge = document.getElementById(`badge-${ep.id}`);
        const latVal = document.getElementById(`lat-${ep.id}`);
        const bar = document.getElementById(`bar-${ep.id}`);

        try {
            const res = await fetch(ep.url);
            const t1 = performance.now();
            const latency = Math.round(t1 - t0);
            const data = await res.json();

            // Update UI
            badge.textContent = 'Online';
            badge.className = 'status-badge online';
            latVal.textContent = `${latency}ms`;

            // Latency bar (max 500ms for 100% width for visualization)
            const width = Math.min((latency / 500) * 100, 100);
            bar.style.width = `${width}%`;
            bar.style.background = latency > 200 ? '#f59e0b' : '#3b82f6';

            // Detailed data mapping
            if (ep.id === 'health' && data.success) {
                document.getElementById('val-backend').textContent = 'Active';
                document.getElementById('val-db').textContent = (data.data.database || 'unknown').toUpperCase();
                document.getElementById('val-uptime').textContent = data.data.uptime;
            } else if (ep.id === 'pipeline' && data.success) {
                document.getElementById('val-cache').textContent = data.data.runTimestamp ? 'Hit' : 'Miss';
                document.getElementById('val-lastrun').textContent = data.data.runTimestamp || 'Never';
            } else if (ep.id === 'config' && data.success) {
                const cfg = data.data;
                document.getElementById('val-budget').textContent = `$${cfg.budget.toLocaleString()}`;
                document.getElementById('val-horizon').textContent = `${cfg.horizon} Days`;
                document.getElementById('val-k').textContent = `${cfg.kMin}-${cfg.kMax}`;
            }

        } catch (err) {
            badge.textContent = 'Offline';
            badge.className = 'status-badge offline';
            latVal.textContent = 'Error';
            bar.style.width = '100%';
            bar.style.background = '#ef4444';
        }
    });
}

async function fetchData(forceRun = false, budget = null) {
    showLoading();

    try {
        let url = API_URL;
        if (forceRun) {
            url = '/api/pipeline/run';
            if (budget) url += `?budget=${budget}`;
        }

        const response = await fetch(url);
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);

        const { success, data, error } = await response.json();

        if (!success) throw new Error(error || "API returned an error");

        // Render Dashboard
        renderKPIs(data);
        renderForecastChart(data.forecasts);
        renderAllocationChart(data.allocation);
        renderClusterTable(data.clusterSummary);
        renderAccuracyTable(data.accuracy);

        // Update footer last run
        if (data.runTimestamp) {
            dom.footerLastRun.textContent = data.runTimestamp;
        }

        showDashboard();
    } catch (error) {
        console.error("Pipeline Error:", error);
        showError(error.message);
    }
}

// ── UI State Management ───────────────────────────────────────────────────────
function showLoading() {
    dom.loadingOverlay.classList.remove('hidden');
    dom.errorState.classList.add('hidden');
    dom.dashboardData.classList.add('hidden');
    dom.refreshBtn.disabled = true;
}

function showDashboard() {
    setTimeout(() => {
        dom.loadingOverlay.classList.add('hidden');
        dom.dashboardData.classList.remove('hidden');
        dom.refreshBtn.disabled = false;

        // Trigger reflow for animations
        document.querySelectorAll('.glass-panel').forEach((el, index) => {
            el.style.animationDelay = `${index * 0.1}s`;
            el.classList.add('fade-up');
        });
    }, 500); // Artificial delay to show loading animation briefly
}

function showError(msg) {
    dom.loadingOverlay.classList.add('hidden');
    dom.dashboardData.classList.add('hidden');
    dom.errorState.classList.remove('hidden');
    dom.errorMsg.textContent = msg;
    dom.refreshBtn.disabled = false;
}

// ── Rendering Functions ───────────────────────────────────────────────────────

function renderKPIs(data) {
    const formatter = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

    // Dynamic budget from API configuration
    dom.kpiBudget.textContent = formatter.format(data.totalBudget || 50000);
    dom.budgetInput.value = data.totalBudget || 50000;

    dom.kpiCost.textContent = formatter.format(data.totalCost || 0);

    // Calculate Average Coverage
    if (data.allocation && data.allocation.length > 0) {
        const avgCov = data.allocation.reduce((sum, item) => sum + item.coverage, 0) / data.allocation.length;
        dom.kpiCoverage.textContent = `${avgCov.toFixed(1)}%`;
    } else {
        dom.kpiCoverage.textContent = "0%";
    }

    if (data.feasible) {
        dom.kpiFeasible.textContent = "Optimal Setup";
        dom.kpiFeasible.style.background = "rgba(16, 185, 129, 0.2)";
        dom.kpiFeasible.style.color = "#10b981";
    } else {
        dom.kpiFeasible.textContent = "Fallback Setup";
        dom.kpiFeasible.style.background = "rgba(239, 68, 68, 0.2)";
        dom.kpiFeasible.style.color = "#ef4444";
    }
}

function renderForecastChart(forecastsData) {
    const ctx = document.getElementById('forecastChart');
    if (forecastChartInstance) forecastChartInstance.destroy();

    const datasets = [];
    const clusters = Object.keys(forecastsData);

    // Assume all clusters have same labels (dates)
    let labels = [];
    if (clusters.length > 0) {
        labels = forecastsData[clusters[0]].labels;
    }

    clusters.forEach((cid, index) => {
        const cData = forecastsData[cid];
        const color = clusterColors[index % clusterColors.length];

        // Actuals Line (Solid)
        datasets.push({
            label: `Cluster ${cid} Actual`,
            data: cData.actuals,
            borderColor: color.border,
            backgroundColor: 'transparent',
            borderWidth: 2,
            tension: 0.3,
            pointRadius: 0
        });

        // Forecast Line (Dashed, filled area)
        datasets.push({
            label: `Cluster ${cid} Forecast`,
            data: cData.forecasts,
            borderColor: color.border,
            backgroundColor: color.bg,
            borderWidth: 2,
            borderDash: [5, 5],
            tension: 0.3,
            fill: true,
            pointRadius: 0
        });
    });

    Chart.defaults.color = '#94a3b8';
    Chart.defaults.font.family = 'Inter';

    forecastChartInstance = new Chart(ctx, {
        type: 'line',
        data: { labels, datasets },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { position: 'bottom', labels: { boxWidth: 12, usePointStyle: true } },
                tooltip: { backgroundColor: 'rgba(15, 23, 42, 0.9)', titleColor: '#fff', bodyColor: '#cbd5e1' }
            },
            scales: {
                x: {
                    grid: { color: 'rgba(51, 65, 85, 0.5)', drawBorder: false },
                    ticks: { maxTicksLimit: 10 }
                },
                y: {
                    beginAtZero: true,
                    grid: { color: 'rgba(51, 65, 85, 0.5)', drawBorder: false }
                }
            }
        }
    });
}

function renderAllocationChart(allocationData) {
    const ctx = document.getElementById('allocationChart');
    if (allocationChartInstance) allocationChartInstance.destroy();

    const labels = allocationData.map(d => `Cluster ${d.id}`);
    const demand = allocationData.map(d => d.demand);
    const allocated = allocationData.map(d => d.units);

    allocationChartInstance = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [
                {
                    label: '30-Day Demand',
                    data: demand,
                    backgroundColor: 'rgba(71, 85, 105, 0.5)', // Slate
                    borderRadius: 4
                },
                {
                    label: 'Allocated Units',
                    data: allocated,
                    backgroundColor: '#3b82f6', // Blue
                    borderRadius: 4
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { position: 'bottom' }
            },
            scales: {
                x: { grid: { display: false } },
                y: { beginAtZero: true, grid: { color: 'rgba(51, 65, 85, 0.5)' } }
            }
        }
    });
}

function renderClusterTable(clusterSummary) {
    dom.clusterTableBody.innerHTML = '';
    clusterSummary.forEach(c => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>Cluster ${c.id}</td>
            <td>${c.size}</td>
            <td>$${c.avgUnitCost.toFixed(2)}</td>
            <td>${c.avgTotalQty.toFixed(0)}</td>
            <td>$${c.avgRevenue.toFixed(2)}</td>
        `;
        dom.clusterTableBody.appendChild(tr);
    });
}

function renderAccuracyTable(accuracyData) {
    dom.accuracyTableBody.innerHTML = '';
    accuracyData.forEach(a => {
        const tr = document.createElement('tr');

        // highlight high error
        let smapeClass = '';
        if (a.smapePct > 20) smapeClass = 'text-warning';
        if (a.smapePct > 40) smapeClass = 'text-danger';

        tr.innerHTML = `
            <td>Cluster ${a.id}</td>
            <td>${a.mae.toFixed(2)}</td>
            <td>${a.rmse.toFixed(2)}</td>
            <td class="${smapeClass}">${a.smapePct.toFixed(2)}%</td>
        `;
        dom.accuracyTableBody.appendChild(tr);
    });
}

// ── Tab Switching ─────────────────────────────────────────────────────────────
window.switchTab = function (tabId) {
    document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));

    document.getElementById(tabId).classList.add('active');
    event.currentTarget.classList.add('active');
};

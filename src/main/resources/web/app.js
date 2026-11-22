'use strict';
// SlotWise web front end: a small single-page app over the /api JSON endpoints.

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

/** Builds a DOM element. Text children are inserted as text, never as HTML. */
function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
        if (v == null || v === false) continue;
        if (k === 'class') el.className = v;
        else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
        else if (k in el && k !== 'list') el[k] = v;
        else el.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat(Infinity)) {
        if (c == null || c === false) continue;
        el.append(c instanceof Node ? c : String(c));
    }
    return el;
}

async function api(method, path, body) {
    const res = await fetch(path, {
        method,
        headers: body === undefined ? {} : {'Content-Type': 'application/json'},
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (res.status === 204) return null;
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
}

let toastTimer;
function toast(message, bad = false) {
    const t = document.getElementById('toast');
    t.textContent = message;
    t.className = 'show' + (bad ? ' bad' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.className = bad ? 'bad' : ''), bad ? 5000 : 2500);
}

function plural(n, word) {
    return `${n} ${n === 1 ? word : word.endsWith('ch') ? word + 'es' : word + 's'}`;
}

function field(label, input, hint) {
    return h('div', {class: 'field'}, h('label', {}, label), input, hint ? h('div', {class: 'hint'}, hint) : null);
}

function select(options, value) {
    return h('select', {}, options.map(([v, text]) => h('option', {value: String(v), selected: String(v) === String(value)}, text)));
}

function hashParams() {
    const q = location.hash.split('?')[1] || '';
    return Object.fromEntries(new URLSearchParams(q));
}

// ---------------------------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------------------------

const routes = {};
let leaveCurrent = () => {};

async function render() {
    const name = (location.hash.replace(/^#\//, '').split('?')[0]) || 'dashboard';
    const route = routes[name] ? name : 'dashboard';
    leaveCurrent();
    leaveCurrent = () => {};
    for (const a of document.querySelectorAll('#tabs a')) a.classList.toggle('active', a.dataset.route === route);
    const main = document.getElementById('view');
    main.replaceChildren(h('div', {class: 'spinner'}, 'Loading...'));
    try {
        await routes[route](main);
    } catch (e) {
        main.replaceChildren(h('div', {class: 'error-box'}, e.message));
    }
}

window.addEventListener('hashchange', render);
window.addEventListener('DOMContentLoaded', async () => {
    try {
        const s = await api('GET', '/api/summary');
        const badge = document.getElementById('db-badge');
        badge.textContent = s.database.startsWith('Embedded') ? 'Embedded database' : s.database;
        badge.title = s.database;
    } catch (e) { /* the page itself will show the error */ }
    render();
});

// ---------------------------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------------------------

routes.dashboard = async (main) => {
    const s = await api('GET', '/api/summary');
    const c = s.counts;
    const empty = c.rooms === 0 && c.batches === 0 && c.faculty === 0;
    const loadSample = h('button', {
        class: 'btn', onclick: async () => {
            if (!empty && !confirm('Replace ALL data, including saved timetables, with the sample department?')) return;
            await api('POST', '/api/sample');
            toast('Sample department loaded.');
            render();
        }
    }, empty ? 'Load the sample department' : 'Replace with sample data');

    const stat = (num, label, href) => h('a', {class: 'card stat', href, style: {textDecoration: 'none', color: 'inherit'}},
        h('div', {class: 'num'}, num), h('div', {class: 'label'}, label));
    main.replaceChildren(
        h('div', {class: 'card hero'},
            h('div', {},
                h('h1', {}, 'SlotWise'),
                h('p', {}, 'Builds clash-free weekly timetables for a whole department. It proves when no timetable can exist, ' +
                    'finds one by backtracking search when it can, then polishes it with simulated annealing.')),
            h('div', {class: 'cta'},
                h('a', {class: 'btn primary big', href: '#/generate'}, 'Generate timetable'),
                s.latestRun ? h('a', {class: 'btn big', href: '#/timetable'}, 'View latest') : null)),
        h('div', {class: 'stats'},
            stat(c.rooms, 'rooms', '#/rooms'),
            stat(c.batches, `batches (+${c.labGroups} lab groups)`, '#/batches'),
            stat(c.faculty, 'faculty', '#/faculty'),
            stat(c.courses, 'courses', '#/courses'),
            stat(c.meetings, `weekly meetings (${c.periods} periods)`, '#/courses'),
            stat(`${s.week.days} x ${s.week.periodsPerDay}`, 'days x periods', '#/generate')),
        h('div', {class: 'two-col'},
            h('div', {class: 'card'},
                h('h2', {}, 'Latest timetable'),
                s.latestRun
                    ? h('div', {},
                        h('p', {}, h('strong', {}, s.latestRun.label), h('span', {class: 'muted'}, `  (run #${s.latestRun.id}, ${s.latestRun.createdAt})`)),
                        h('p', {class: 'muted'}, `Soft cost ${s.latestRun.softCost.toFixed(1)}. Lower is better.`),
                        h('a', {class: 'btn', href: `#/timetable?run=${s.latestRun.id}`}, 'Open'))
                    : h('p', {class: 'muted'}, 'No timetable yet. Add your data, then click Generate.'),
                h('div', {style: {marginTop: '14px'}}, loadSample)),
            h('div', {class: 'card'},
                h('h2', {}, 'How it works'),
                h('ol', {class: 'steps'},
                    h('li', {}, 'Each course is split into its weekly meetings. Meetings sharing a batch or teacher conflict: ' +
                        'a graph-colouring problem where time slots are the colours.'),
                    h('li', {}, 'Before searching, SlotWise looks for proofs that no timetable exists (maximum clique, ' +
                        "Hall's theorem on slots, rooms and long sessions, daily limits) and explains them."),
                    h('li', {}, 'A backtracking search (fewest options first, forward checking, exact room matching) finds a clash-free timetable.'),
                    h('li', {}, 'Simulated annealing removes idle gaps, evens out teachers\' days, spreads sessions, cuts building changes and keeps small classes out of big halls, ' +
                        'never breaking a hard constraint.')))));
};

// ---------------------------------------------------------------------------------------------
// Data pages: one generic table with add / edit / delete, configured per entity
// ---------------------------------------------------------------------------------------------

function entityPage(cfg) {
    return async (main) => {
        const ctx = await cfg.load();
        let needle = '';
        const tbody = h('tbody');
        const count = h('span', {class: 'muted'});
        const draw = () => {
            const items = ctx.items.filter(it => cfg.describe(it, ctx).toLowerCase().includes(needle));
            count.textContent = plural(ctx.items.length, cfg.noun);
            tbody.replaceChildren(...(items.length ? items.map(it => h('tr', {ondblclick: () => edit(it)},
                cfg.columns.map(([, value]) => h('td', {}, value(it, ctx))),
                h('td', {class: 'actions'},
                    h('button', {class: 'btn small', onclick: () => edit(it)}, 'Edit'), ' ',
                    h('button', {class: 'btn small danger', onclick: () => remove(it)}, 'Delete'))))
                : [h('tr', {class: 'empty-row'}, h('td', {colSpan: cfg.columns.length + 1},
                    ctx.items.length ? 'Nothing matches your search.' : `No ${plural(0, cfg.noun).slice(2)} yet. Click "Add" to create one.`))]));
        };
        const reload = async () => {
            Object.assign(ctx, await cfg.load());
            draw();
        };
        const edit = (it) => openForm(it ? `Edit ${cfg.describe(it, ctx)}` : `Add ${cfg.noun}`, cfg.form(it, ctx), async (value) => {
            if (it) await api('PUT', `${cfg.endpoint}/${it.id}`, value);
            else await api('POST', cfg.endpoint, value);
            toast(it ? 'Saved.' : `Added ${cfg.noun}.`);
            await reload();
        });
        const remove = async (it) => {
            if (!confirm(`Delete ${cfg.describe(it, ctx)}?`)) return;
            try {
                await api('DELETE', `${cfg.endpoint}/${it.id}`);
                toast('Deleted.');
                await reload();
            } catch (e) {
                toast(e.message, true);
            }
        };
        main.replaceChildren(
            h('div', {class: 'page-head'},
                h('h1', {}, cfg.title), count, h('div', {class: 'spacer'}),
                h('input', {class: 'search', type: 'search', placeholder: `Search ${plural(2, cfg.noun).slice(2)}`,
                    oninput: e => { needle = e.target.value.trim().toLowerCase(); draw(); }}),
                h('button', {class: 'btn primary', onclick: () => edit(null)}, `Add ${cfg.noun}`)),
            h('div', {class: 'table-wrap'}, h('table', {},
                h('thead', {}, h('tr', {}, cfg.columns.map(([label]) => h('th', {}, label)), h('th'))), tbody)));
        draw();
    };
}

/** Opens a modal form. {@code body} is {node, read()}; errors from save keep the dialog open. */
function openForm(title, body, save) {
    const dlg = document.getElementById('modal');
    const error = h('p', {class: 'form-error'});
    const form = h('form', {
        onsubmit: async (e) => {
            e.preventDefault();
            error.textContent = '';
            try {
                await save(body.read());
                dlg.close();
            } catch (err) {
                error.textContent = err.message;
            }
        }
    },
        h('h2', {}, title), body.node, error,
        h('div', {class: 'dialog-actions'},
            h('button', {type: 'button', class: 'btn', onclick: () => dlg.close()}, 'Cancel'),
            h('button', {type: 'submit', class: 'btn primary'}, 'Save')));
    dlg.replaceChildren(form);
    dlg.showModal();
    const first = form.querySelector('input, select');
    if (first) first.focus();
}

const num = (input) => input.value.trim() === '' ? NaN : Number(input.value);

// ---- rooms ----

routes.rooms = entityPage({
    title: 'Rooms', noun: 'room', endpoint: '/api/rooms',
    load: async () => ({items: await api('GET', '/api/rooms')}),
    describe: r => `${r.code} ${r.building}`,
    columns: [
        ['Code', r => h('strong', {}, r.code)],
        ['Building', r => r.building],
        ['Capacity', r => r.capacity],
        ['Type', r => h('span', {class: 'badge' + (r.type === 'LAB' ? ' lab' : '')}, r.type)],
    ],
    form: (r, ctx) => {
        const code = h('input', {value: r ? r.code : '', required: true});
        const buildings = [...new Set(ctx.items.map(x => x.building))].sort();
        const building = h('input', {value: r ? r.building : '', list: 'buildings', required: true});
        const capacity = h('input', {type: 'number', min: 1, max: 10000, value: r ? r.capacity : 60});
        const type = select([['LECTURE', 'Lecture room'], ['LAB', 'Lab']], r ? r.type : 'LECTURE');
        return {
            node: h('div', {},
                h('datalist', {id: 'buildings'}, buildings.map(b => h('option', {value: b}))),
                field('Room code', code), field('Building', building), field('Seating capacity', capacity), field('Room type', type)),
            read: () => ({code: code.value, building: building.value, capacity: num(capacity), type: type.value}),
        };
    },
});

// ---- batches ----

routes.batches = entityPage({
    title: 'Batches', noun: 'batch', endpoint: '/api/batches',
    load: async () => {
        const [items, courses] = await Promise.all([api('GET', '/api/batches'), api('GET', '/api/courses')]);
        return {items, courses};
    },
    describe: b => b.name,
    columns: [
        ['Name', b => h('strong', {}, b.name)],
        ['Students', b => b.studentCount],
        ['Lab group of', (b, ctx) => b.parentId ? h('span', {class: 'badge group'}, nameOf(ctx.items, b.parentId)) : ''],
        ['Courses', (b, ctx) => ctx.courses.filter(c => c.batchIds.includes(b.id)).length],
        ['Weekly periods', (b, ctx) => {
            const own = id => ctx.courses.filter(c => c.batchIds.includes(id)).reduce((s, c) => s + c.meetingsPerWeek * c.periodsPerMeeting, 0);
            return own(b.id) + (b.parentId ? own(b.parentId) : 0);
        }],
    ],
    form: (b, ctx) => {
        const name = h('input', {value: b ? b.name : '', placeholder: 'e.g. CSE-3A'});
        const students = h('input', {type: 'number', min: 1, max: 10000, value: b ? b.studentCount : 60});
        const hasGroups = b && ctx.items.some(x => x.parentId === b.id);
        const parents = ctx.items.filter(x => !x.parentId && (!b || x.id !== b.id)).sort((x, y) => x.name.localeCompare(y.name));
        const parent = select([['', '(none: a whole batch)'], ...parents.map(x => [x.id, x.name])], b && b.parentId ? b.parentId : '');
        parent.disabled = !!hasGroups;
        return {
            node: h('div', {},
                field('Batch name', name), field('Number of students', students),
                field('Lab group of', parent, hasGroups
                    ? 'This batch has lab groups, so it cannot itself be a lab group.'
                    : 'A lab group (e.g. CSE-1A G1) takes its own labs and attends every class of its batch.')),
            read: () => ({name: name.value, studentCount: num(students), parentId: parent.value ? Number(parent.value) : null}),
        };
    },
});

function nameOf(list, id) {
    const x = list.find(i => i.id === id);
    return x ? x.name : '?';
}

// ---- faculty ----

routes.faculty = entityPage({
    title: 'Faculty', noun: 'faculty member', endpoint: '/api/faculty',
    load: async () => {
        const [items, courses, summary] = await Promise.all([api('GET', '/api/faculty'), api('GET', '/api/courses'), api('GET', '/api/summary')]);
        return {items, courses, week: summary.week};
    },
    describe: f => f.name,
    columns: [
        ['Name', f => h('strong', {}, f.name)],
        ['Weekly periods', (f, ctx) => ctx.courses.filter(c => c.facultyId === f.id).reduce((s, c) => s + c.meetingsPerWeek * c.periodsPerMeeting, 0)],
        ['Max per day', f => f.maxPerDay > 0 ? f.maxPerDay : h('span', {class: 'muted'}, 'no limit')],
        ['Unavailable', (f, ctx) => unavailableSummary(f, ctx.week) || h('span', {class: 'muted'}, '-')],
    ],
    form: (f, ctx) => {
        const name = h('input', {value: f ? f.name : ''});
        const limit = h('input', {type: 'number', min: 0, max: 16, value: f ? f.maxPerDay : 0, style: {width: '90px'}});
        const off = new Set((f ? f.unavailable : []).map(t => `${t.day}:${t.period}`));
        const {days, periodsPerDay, dayNames} = ctx.week;
        const grid = h('div', {class: 'slots', style: {gridTemplateColumns: `44px repeat(${periodsPerDay}, 1fr)`}});
        grid.append(h('div'));
        for (let p = 0; p < periodsPerDay; p++) grid.append(h('div', {class: 'head'}, `P${p + 1}`));
        for (let d = 0; d < days; d++) {
            grid.append(h('div', {class: 'head', style: {textAlign: 'left', alignSelf: 'center'}}, dayNames[d]));
            for (let p = 0; p < periodsPerDay; p++) {
                const key = `${d}:${p}`;
                const btn = h('button', {type: 'button', class: 'slot' + (off.has(key) ? ' off' : ''), title: `${dayNames[d]} P${p + 1}`,
                    onclick: () => { off.has(key) ? off.delete(key) : off.add(key); btn.classList.toggle('off'); btn.textContent = off.has(key) ? 'off' : ''; }},
                    off.has(key) ? 'off' : '');
                grid.append(btn);
            }
        }
        return {
            node: h('div', {},
                field('Name', name),
                field('Daily limit', limit, 'Most periods this person may teach in one day (0 = no limit).'),
                h('p', {class: 'muted', style: {margin: '4px 0'}}, 'Click the slots in which this person cannot teach.'),
                grid),
            read: () => ({
                name: name.value, maxPerDay: num(limit),
                unavailable: [...off].map(k => ({day: Number(k.split(':')[0]), period: Number(k.split(':')[1])})),
            }),
        };
    },
});

function unavailableSummary(f, week) {
    const byDay = {};
    for (const t of f.unavailable) {
        if (t.day < week.days && t.period < week.periodsPerDay) (byDay[t.day] = byDay[t.day] || []).push(t.period);
    }
    return Object.keys(byDay).sort().map(d => {
        const ps = byDay[d].sort((a, b) => a - b);
        return `${week.dayNames[d]} ${ps.length === week.periodsPerDay ? 'all day' : ps.map(p => 'P' + (p + 1)).join(',')}`;
    }).join('; ');
}

// ---- courses ----

routes.courses = entityPage({
    title: 'Courses', noun: 'course', endpoint: '/api/courses',
    load: async () => {
        const [items, batches, faculty] = await Promise.all([api('GET', '/api/courses'), api('GET', '/api/batches'), api('GET', '/api/faculty')]);
        return {items, batches, faculty};
    },
    describe: (c, ctx) => `${c.code} ${c.title} ${c.batchIds.map(id => nameOf(ctx.batches, id)).join(' ')} ${nameOf(ctx.faculty, c.facultyId)}`,
    columns: [
        ['Code', c => h('strong', {}, c.code)],
        ['Title', c => c.title],
        ['Batches', (c, ctx) => c.batchIds.map(id => nameOf(ctx.batches, id)).join(' + ')],
        ['Faculty', (c, ctx) => nameOf(ctx.faculty, c.facultyId)],
        ['Meetings / week', c => c.meetingsPerWeek],
        ['Length', c => c.periodsPerMeeting === 1 ? '1 period' : h('span', {class: 'badge lab'}, `${c.periodsPerMeeting} periods`)],
        ['Room', c => h('span', {class: 'badge' + (c.roomType === 'LAB' ? ' lab' : '')}, c.roomType)],
    ],
    form: (c, ctx) => {
        if (!ctx.batches.length || !ctx.faculty.length) {
            return {node: h('p', {}, 'Add batches and faculty first: a course needs a batch to attend it and a teacher to teach it.'),
                read: () => { throw new Error('Add batches and faculty first.'); }};
        }
        const code = h('input', {value: c ? c.code : ''});
        const title = h('input', {value: c ? c.title : ''});
        const chosen = new Set(c ? c.batchIds : []);
        const hint = h('div', {class: 'hint'});
        const updateHint = () => {
            const picked = ctx.batches.filter(b => chosen.has(b.id));
            const students = picked.reduce((s, b) => s + b.studentCount, 0);
            hint.textContent = picked.length > 1
                ? `Combined class: all ${picked.length} batches attend every meeting together (${students} students).`
                : 'Tick more than one batch for a combined class or shared elective.';
        };
        const checks = h('div', {class: 'checks'}, [...ctx.batches].sort((a, b) => a.name.localeCompare(b.name)).map(b =>
            h('label', {}, h('input', {type: 'checkbox', checked: chosen.has(b.id),
                onchange: e => { e.target.checked ? chosen.add(b.id) : chosen.delete(b.id); updateHint(); }}), `${b.name} (${b.studentCount})`)));
        updateHint();
        const teacher = select([['', 'Choose...'], ...[...ctx.faculty].sort((a, b) => a.name.localeCompare(b.name)).map(f => [f.id, f.name])],
            c ? c.facultyId : '');
        const meetings = h('input', {type: 'number', min: 1, max: 20, value: c ? c.meetingsPerWeek : 3});
        const length = select([[1, '1 period'], [2, '2 periods (double)'], [3, '3 periods (triple)']], c ? c.periodsPerMeeting : 1);
        const type = select([['LECTURE', 'Lecture room'], ['LAB', 'Lab']], c ? c.roomType : 'LECTURE');
        return {
            node: h('div', {},
                field('Course code', code), field('Title', title),
                h('div', {class: 'field'}, h('label', {}, 'Batches'), checks, hint),
                field('Faculty', teacher), field('Meetings per week', meetings),
                field('Length', length, 'Consecutive periods per meeting, in one room on one day.'),
                field('Room type', type)),
            read: () => {
                if (!teacher.value) throw new Error('Choose who teaches this course.');
                return {
                    code: code.value, title: title.value, facultyId: Number(teacher.value), batchIds: [...chosen],
                    meetingsPerWeek: num(meetings), periodsPerMeeting: Number(length.value), roomType: type.value,
                };
            },
        };
    },
});

// ---------------------------------------------------------------------------------------------
// Generate
// ---------------------------------------------------------------------------------------------

routes.generate = async (main) => {
    const s = await api('GET', '/api/summary');
    const input = (value, attrs = {}) => h('input', {type: 'number', value, ...attrs});
    const f = {
        days: input(s.week.days, {min: 1, max: 7}), periodsPerDay: input(s.week.periodsPerDay, {min: 1, max: 12}),
        lunchAfter: input(s.week.lunchAfter, {min: 0, max: 11}),
        timeLimitSeconds: input(30, {min: 1}), cliqueSeconds: input(2, {min: 0}),
        iterations: input(1000000, {min: 0, step: 100000}), seed: input(42),
        idleGap: input(3, {min: 0, step: 0.5}), loadSpread: input(1, {min: 0, step: 0.5}),
        sessionSpacing: input(4, {min: 0, step: 0.5}), buildingChange: input(2, {min: 0, step: 0.5}),
        roomFit: input(1, {min: 0, step: 0.5}),
    };
    const label = h('input', {placeholder: 'defaults to date and time'});
    const go = h('button', {class: 'btn primary', type: 'submit'}, 'Generate timetable');
    const cancel = h('button', {class: 'btn', type: 'button', disabled: true, onclick: () => api('POST', '/api/generate/cancel')}, 'Cancel');

    const stage = h('div', {class: 'muted'}, 'Ready.');
    const bar = h('div');
    const progress = h('div', {class: 'progress'}, bar);
    const summary = h('div');
    const chartBox = h('div', {class: 'chart'});
    const log = h('pre', {class: 'log'}, 'The log of the run appears here.');

    const form = h('form', {class: 'card', onsubmit: async (e) => {
        e.preventDefault();
        const body = {label: label.value};
        for (const [k, el] of Object.entries(f)) if (el.value !== '') body[k] = Number(el.value);
        try {
            await api('POST', '/api/generate', body);
            poll();
        } catch (err) {
            toast(err.message, true);
        }
    }},
        h('h3', {}, 'Week'), field('Days per week', f.days), field('Periods per day', f.periodsPerDay),
        field('Lunch after period (0 = none)', f.lunchAfter),
        h('h3', {}, 'Search (hard constraints)'), field('Time limit (s)', f.timeLimitSeconds), field('Clique bound (s)', f.cliqueSeconds),
        h('h3', {}, 'Annealing (soft constraints)'), field('Iterations', f.iterations), field('Random seed', f.seed),
        h('h3', {}, 'Soft-constraint weights'), field('Batch idle gap', f.idleGap), field('Faculty load spread', f.loadSpread),
        field('Session spacing', f.sessionSpacing), field('Building change', f.buildingChange), field('Room fit', f.roomFit),
        h('h3', {}, 'Save as'), h('div', {class: 'field', style: {gridTemplateColumns: '1fr'}}, label),
        h('div', {style: {display: 'flex', gap: '8px', marginTop: '8px'}}, go, cancel));

    main.replaceChildren(h('div', {class: 'gen'}, form,
        h('div', {class: 'card', style: {display: 'flex', flexDirection: 'column', gap: '12px'}},
            stage, progress, summary,
            h('div', {}, h('h2', {style: {margin: '4px 0'}}, 'Soft-constraint cost'), chartBox,
                h('div', {class: 'legend'}, h('span', {style: {color: '#f97316'}}, 'current'), h('span', {style: {color: '#2563eb'}}, 'best so far'))),
            log)));

    let timer = null;
    const show = (st) => {
        stage.textContent = st.stage;
        progress.className = 'progress' + (st.running && st.progress < 0 ? ' indeterminate' : '');
        bar.style.width = st.progress < 0 ? '' : `${Math.round(st.progress * 100)}%`;
        go.disabled = st.running;
        cancel.disabled = !st.running;
        chartBox.replaceChildren(costChart(st.trace));
        log.textContent = st.log.length ? st.log.join('\n') : 'The log of the run appears here.';
        log.scrollTop = log.scrollHeight;
        if (st.running || !st.outcome) {
            summary.replaceChildren();
        } else if (st.runId) {
            summary.replaceChildren(h('div', {class: 'summary good'}, st.summary, ' ',
                h('a', {class: 'btn small primary', href: `#/timetable?run=${st.runId}`, style: {marginLeft: '8px'}}, 'View timetable')));
        } else {
            summary.replaceChildren(h('div', {class: 'summary bad'}, st.error || st.summary || st.outcomeText,
                st.reasons && st.reasons.length ? h('ul', {}, st.reasons.map(r => h('li', {}, r))) : null));
        }
    };
    const poll = async () => {
        clearTimeout(timer);
        const st = await api('GET', '/api/generate');
        show(st);
        if (st.running) timer = setTimeout(poll, 400);
    };
    leaveCurrent = () => clearTimeout(timer);
    await poll();
};

/** Line chart of the annealing trace: rows are [iteration, current, best]. */
function costChart(trace) {
    const W = 640, H = 220, L = 52, R = 12, T = 10, B = 30;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
    const add = (tag, attrs, text) => {
        const el = document.createElementNS(ns, tag);
        for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
        if (text != null) el.textContent = text;
        svg.append(el);
        return el;
    };
    add('rect', {x: L, y: T, width: W - L - R, height: H - T - B, fill: '#f8fafc', stroke: '#e2e8f0'});
    if (!trace || trace.length < 2) {
        add('text', {x: W / 2, y: H / 2, 'text-anchor': 'middle', fill: '#94a3b8', 'font-size': 13}, 'The cost curve appears while annealing runs.');
        return svg;
    }
    const maxX = Math.max(...trace.map(t => t[0])) || 1;
    const ys = trace.flatMap(t => [t[1], t[2]]);
    const minY = Math.min(...ys), maxY = Math.max(...ys) || 1;
    const x = v => L + (v / maxX) * (W - L - R);
    const y = v => T + (1 - (v - minY) / ((maxY - minY) || 1)) * (H - T - B);
    for (const [v, anchor] of [[maxY, T + 4], [minY, H - B]]) add('text', {x: L - 6, y: anchor + 4, 'text-anchor': 'end', fill: '#64748b', 'font-size': 11}, Math.round(v));
    add('text', {x: W - R, y: H - 8, 'text-anchor': 'end', fill: '#64748b', 'font-size': 11}, `${maxX.toLocaleString()} iterations`);
    add('polyline', {points: trace.map(t => `${x(t[0])},${y(t[1])}`).join(' '), fill: 'none', stroke: '#f97316', 'stroke-width': 1.5});
    add('polyline', {points: trace.map(t => `${x(t[0])},${y(t[2])}`).join(' '), fill: 'none', stroke: '#2563eb', 'stroke-width': 2});
    return svg;
}

// ---------------------------------------------------------------------------------------------
// Timetable
// ---------------------------------------------------------------------------------------------

routes.timetable = async (main) => {
    const runs = await api('GET', '/api/runs');
    if (!runs.length) {
        main.replaceChildren(h('div', {class: 'card'}, h('h2', {}, 'No timetable yet'),
            h('p', {class: 'muted'}, 'Generate one first.'), h('a', {class: 'btn primary', href: '#/generate'}, 'Go to Generate')));
        return;
    }
    const params = hashParams();
    const state = {
        run: Number(params.run) || runs[0].id,
        view: params.view || 'BATCH',
        subject: params.subject || '',
    };
    if (!runs.some(r => r.id === state.run)) state.run = runs[0].id;
    const data = await api('GET', `/api/runs/${state.run}/grid?view=${state.view}&subject=${state.subject}`);
    state.subject = data.subject ? String(data.subject.id) : '';

    const go = (patch) => {
        Object.assign(state, patch);
        location.hash = `#/timetable?run=${state.run}&view=${state.view}&subject=${state.subject}`;
    };
    const runSel = select(runs.map(r => [r.id, `#${r.id}  ${r.label}  (cost ${r.softCost.toFixed(1)})`]), state.run);
    runSel.onchange = () => go({run: Number(runSel.value)});
    const viewSel = select([['BATCH', 'Batch'], ['FACULTY', 'Faculty'], ['ROOM', 'Room']], state.view);
    viewSel.onchange = () => go({view: viewSel.value, subject: ''});
    const subjectSel = select(data.subjects.map(s => [s.id, s.name]), state.subject);
    subjectSel.onchange = () => go({subject: subjectSel.value});

    const viewName = {BATCH: 'Batch', FACULTY: 'Faculty', ROOM: 'Room'}[data.view];
    const st = data.status;
    main.replaceChildren(
        h('div', {class: 'toolbar'},
            h('label', {}, 'Run '), runSel,
            h('div', {class: 'sep'}),
            h('label', {}, 'View by '), viewSel, subjectSel,
            h('div', {class: 'sep'}),
            h('button', {class: 'btn', onclick: () => window.print(), title: "Use your browser's \"Save as PDF\" destination for a PDF"}, 'Print / Save as PDF'),
            h('button', {class: 'btn', onclick: () => printEvery(state, data, viewName)}, `Print every ${viewName.toLowerCase()}`),
            h('a', {class: 'btn', href: `/api/runs/${state.run}/csv`}, 'Download CSV'),
            h('button', {class: 'btn danger', onclick: async () => {
                if (!confirm(`Delete timetable #${state.run}?`)) return;
                await api('DELETE', `/api/runs/${state.run}`);
                toast('Timetable deleted.');
                go({run: 0, subject: ''});
            }}, 'Delete run')),
        h('div', {class: 'print-only'}, h('h2', {}, `${viewName}: ${data.subject ? data.subject.name : ''}`),
            h('p', {class: 'muted'}, `SlotWise | ${data.run.label}`)),
        h('div', {class: 'tt-wrap'}, gridNode(data)),
        h('div', {class: 'status summary ' + (st.valid ? 'good' : 'bad')},
            st.valid
                ? `All hard constraints satisfied (re-checked against the current data). ${st.placed} of ${st.total} meetings placed. Soft cost: ${st.cost}`
                : h('div', {}, `This timetable no longer matches the current data (${plural(st.problemCount, 'problem')}). Generate a new one.`,
                    h('ul', {}, st.problems.slice(0, 5).map(p => h('li', {}, p))))));
};

function gridNode(data) {
    const {dayNames, periodsPerDay, lunchAfter} = data.week;
    // A narrow Lunch column sits after period lunchAfter (0 = no break).
    const columns = ['56px'];
    for (let p = 0; p < periodsPerDay; p++) {
        columns.push('minmax(120px, 1fr)');
        if (p + 1 === lunchAfter) columns.push('44px');
    }
    const grid = h('div', {class: 'tt-grid', style: {gridTemplateColumns: columns.join(' ')}});
    grid.append(h('div'));
    for (let p = 0; p < periodsPerDay; p++) {
        grid.append(h('div', {class: 'tt-head'}, `P${p + 1}`));
        if (p + 1 === lunchAfter) grid.append(h('div', {class: 'tt-head lunch-head'}, 'Lunch'));
    }
    data.rows.forEach((row, d) => {
        grid.append(h('div', {class: 'tt-day'}, dayNames[d]));
        row.forEach((cell, p) => {
            gridCell(grid, cell);
            if (p + 1 === lunchAfter) grid.append(h('div', {class: 'tt-lunch', title: 'Lunch break: no class runs through it'}, 'Lunch'));
        });
    });
    return grid;
}

function gridCell(grid, cell) {
    if (!cell.items.length) {
        grid.append(h('div', {class: 'tt-cell empty'}));
        return;
    }
    grid.append(h('div', {
        class: 'tt-cell' + (cell.clash ? ' clash' : ''),
        style: {background: cell.items[0].colour},
        title: cell.items.map(i => i.tooltip).join('\n\n'),
    }, cell.items.map(i => h('div', {class: 'tt-item'},
        h('strong', {}, i.heading), h('div', {class: 't'}, i.title), h('div', {class: 'd'}, i.details),
        i.joint ? h('div', {class: 'joint'}, i.joint) : null))));
}

/** Prints every timetable in the current view, one page each. */
async function printEvery(state, data, viewName) {
    toast(`Preparing ${plural(data.subjects.length, 'page')}...`);
    const area = document.getElementById('print-area');
    const pages = [];
    for (const s of data.subjects) {
        const g = await api('GET', `/api/runs/${state.run}/grid?view=${state.view}&subject=${s.id}`);
        pages.push(h('section', {class: 'print-page'},
            h('h2', {}, `${viewName}: ${s.name}`), h('p', {class: 'muted'}, `SlotWise | ${g.run.label}`), gridNode(g)));
    }
    area.replaceChildren(...pages);
    document.body.classList.add('print-all');
    const done = () => {
        document.body.classList.remove('print-all');
        area.replaceChildren();
        window.removeEventListener('afterprint', done);
    };
    window.addEventListener('afterprint', done);
    window.print();
}

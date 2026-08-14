import json
import sys
from ortools.sat.python import cp_model
import os
from datetime import datetime

class ScheduleCollector(cp_model.CpSolverSolutionCallback):
    """Custom callback to collect a specific number of valid schedules."""
    def __init__(self, x_vars, limit):
        cp_model.CpSolverSolutionCallback.__init__(self)
        self._x_vars = x_vars
        self._limit = limit
        self.schedules = []
        
    def on_solution_callback(self):
        active_assignments = []
        for (g, s, r, t), var in self._x_vars.items():
            if self.Value(var) == 1:
                active_assignments.append((t, g, s, r))
        self.schedules.append(active_assignments)
        
        if len(self.schedules) >= self._limit:
            self.StopSearch()

def build_and_solve(data, diagnostic_mode=False, max_cost=float('inf')):
    # --- Preprocess Lookups ---
    batch_grade = {b['id']: b['grade'] for b in data['batches']}
    group_batches = {g['id']: g['batches'] for g in data['groups']}
    group_is_atomic = {g['id']: g['isAtomic'] for g in data['groups']}

    all_slots = [f"{t['day']}#{t['slotName']}" for t in data['timeslots']]
    days = list(set(t['day'] for t in data['timeslots']))

    teacher_avail = set()
    teacher_max_weekly = {} 
    
    for t_data in data['teacherAvailability']:
        t_name = t_data['teacherName']
        teacher_max_weekly[t_name] = t_data.get('maxclasses', 999) 
        
        for slot in t_data['availableSlots']:
            teacher_avail.add((t_name, f"{slot['day']}#{slot['slotName']}"))

    reqs = {}
    for r in data['subjectRequirements']:
        reqs[(r['grade'], r['subject'])] = r['requiredSlots']

    model = cp_model.CpModel()

    # --- Create Decision Variables ---
    x_vars = {}
    w_vars = {} 

    for row in data['groupTeachers']:
        g_id = row['groupId']
        subject = row['subject']
        
        if isinstance(row['allowedTeachers'], list):
            if len(row['allowedTeachers']) == 1 and ',' in row['allowedTeachers'][0]:
                allowed_teachers = [t.strip() for t in row['allowedTeachers'][0].split(',')]
            else:
                 allowed_teachers = [t.strip() for t in row['allowedTeachers']]
        else:
             allowed_teachers = [t.strip() for t in row['allowedTeachers'].split(',')]
        
        for teacher in allowed_teachers:
            if (g_id, subject, teacher) not in w_vars:
                w_vars[(g_id, subject, teacher)] = model.NewBoolVar(f"W_{g_id}_{subject}_{teacher}")
                
            for slot in all_slots:
                if (teacher, slot) in teacher_avail:
                    v_name = f"X_{g_id}_{subject}_{teacher}_{slot}".replace("#", "_").replace("-", "_")
                    x_vars[(g_id, subject, teacher, slot)] = model.NewBoolVar(v_name)

    # --- Enforce Hard Constraints ---
    
    # Weekly Cost Limit Constraint
    if max_cost != float('inf'):
        teacher_remuneration = {
            t['teacherName']: t.get('remuneration', 0) 
            for t in data.get('teacherAvailability', [])
        }
        cost_terms = [var * teacher_remuneration.get(teacher, 0) for (g, s, teacher, t), var in x_vars.items()]
        if cost_terms:
            model.Add(sum(cost_terms) <= int(max_cost))
    
    for r, max_limit in teacher_max_weekly.items():
        teacher_week_vars = [var for (g, s, teacher, t), var in x_vars.items() if teacher == r]
        if teacher_week_vars:
            model.Add(sum(teacher_week_vars) <= max_limit)

    for b_data in data['batches']:
        b_id = b_data['id']
        for day in days:
            batch_day_vars = [
                var for (g, s, r, t), var in x_vars.items()
                if b_id in group_batches[g] and t.startswith(f"{day}#")
            ]
            if batch_day_vars:
                model.Add(sum(batch_day_vars) <= 2)

    for t_data in data['teacherAvailability']:
        r = t_data['teacherName']
        for day in days:
            teacher_day_vars = [var for (g, s, teacher, t), var in x_vars.items() if teacher == r and t.startswith(f"{day}#")]
            if teacher_day_vars:
                model.Add(sum(teacher_day_vars) <= 2)

    for t_data in data['teacherAvailability']:
        r = t_data['teacherName']
        for slot in all_slots:
            teacher_slot_vars = [var for (g, s, teacher, t), var in x_vars.items() if teacher == r and t == slot]
            if teacher_slot_vars:
                model.Add(sum(teacher_slot_vars) <= 1)

    for b_data in data['batches']:
        b_id = b_data['id']
        for slot in all_slots:
            batch_slot_vars = [var for (g, s, r, t), var in x_vars.items() if b_id in group_batches[g] and t == slot]
            if batch_slot_vars:
                model.Add(sum(batch_slot_vars) <= 1)


    # --- Requirement Fulfillment ---
    deficit_trackers = []

    for b_data in data['batches']:
        b_id = b_data['id']
        grade = b_data['grade']
        grade_subjects = [s_req['subject'] for s_req in data['subjectRequirements'] if s_req['grade'] == grade]
        
        for subject in grade_subjects:
            req = reqs.get((grade, subject), 0)
            if req == 0:
                continue
                
            valid_g_s_r = [(g, s, r) for (g, s, r) in w_vars.keys() if b_id in group_batches[g] and s == subject]
            batch_w_vars = [w_vars[k] for k in valid_g_s_r]
            
            model.Add(sum(batch_w_vars) <= 1)

            total_assigned_vars = []
            for key in valid_g_s_r:
                g, s, r = key
                w_var = w_vars[key]
                x_list = [var for (xg, xs, xr, xt), var in x_vars.items() if xg == g and xs == s and xr == r]
                
                assigned_var = model.NewIntVar(0, req, f"Ass_{b_id}_{subject}_{g}_{r}")
                model.Add(sum(x_list) == assigned_var)
                model.Add(assigned_var <= req * w_var)
                total_assigned_vars.append(assigned_var)

            deficit = model.NewIntVar(0, req, f"Deficit_{b_id}_{subject}")
            model.Add(deficit == req - sum(total_assigned_vars))
            deficit_trackers.append((b_id, subject, req, deficit))

            if not diagnostic_mode:
                model.Add(deficit == 0)

    # --- Optimization Objective Function ---
    objective_terms = []
    
    for (g, s, r), w_var in w_vars.items():
        if not group_is_atomic[g]:
            objective_terms.append(w_var * 1000)

    # Soft Constraint: Avoid scheduling multiple classes of the same subject on the same day for a batch
    for b_data in data['batches']:
        b_id = b_data['id']
        grade = b_data['grade']
        grade_subjects = [s_req['subject'] for s_req in data['subjectRequirements'] if s_req['grade'] == grade]
        for subject in grade_subjects:
            for day in days:
                sub_day_vars = [
                    var for (g, s, r, t), var in x_vars.items()
                    if b_id in group_batches[g] and s == subject and t.startswith(f"{day}#")
                ]
                if len(sub_day_vars) > 1:
                    excess = model.NewIntVar(0, 1, f"Excess_{b_id}_{subject}_{day}")
                    model.Add(sum(sub_day_vars) - 1 <= excess)
                    objective_terms.append(excess * 50000)

    if diagnostic_mode:
        for b_id, subject, req, deficit_var in deficit_trackers:
            objective_terms.append(deficit_var * 100000)

    model.Minimize(sum(objective_terms))

    # --- Execute Solver ---
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = 45.0 
    status = solver.Solve(model)

    schedules_found = []
    best_score = 0

    if status in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
        best_score = int(solver.ObjectiveValue())
        
        if diagnostic_mode:
            # ONLY GENERATE 1 BEST-EFFORT SCHEDULE
            active_assignments = []
            for (g, s, r, t), var in x_vars.items():
                if solver.Value(var) == 1:
                    active_assignments.append((t, g, s, r))
            schedules_found.append(active_assignments)
        else:
            # ENUMERATE 3 OPTIONS FOR A SUCCESSFUL RUN
            model.ClearObjective()
            
            if objective_terms:
                model.Add(sum(objective_terms) == best_score)
            
            collector = ScheduleCollector(x_vars, limit=3)
            solver.parameters.enumerate_all_solutions = True
            solver.Solve(model, collector)
            
            schedules_found = collector.schedules

    return status, solver, deficit_trackers, group_is_atomic, group_batches, schedules_found, best_score

def save_to_json(active_assignments, group_batches, data, is_optimal, filename):
    """Converts the internal schedule format into a structured JSON file."""
    teacher_remuneration = {
        t['teacherName']: t.get('remuneration', 0) 
        for t in data.get('teacherAvailability', [])
    }
    total_weekly_cost = 0
    
    assignments_list = []
    for slot, group, subject, teacher in active_assignments:
        day, slot_name = slot.split("#")
      
        assignments_list.append({
            "groupId": group,
            "subject": subject,
            "teacher": teacher,
            "day": day,
            "slotName": slot_name,
            "batches": group_batches[group]
        })
        
        total_weekly_cost += teacher_remuneration.get(teacher, 0)

    output_payload = {
        "isOptimalResolution": is_optimal,
        "totalAllocatedClasses": len(assignments_list),
        "totalWeeklyCost": total_weekly_cost,
        "timeslots": data["timeslots"],
        "batches": [b["id"] for b in data["batches"]],
        "assignments": assignments_list
    }
    
    # ADDED: encoding='utf-8'
    with open(filename, 'w', encoding='utf-8') as f:
        json.dump(output_payload, f, indent=2)

def solve_schedule(input_path, report_path, output_dir, log_path, max_cost=float('inf')):
    log_file =log_path
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    #  Ensure the output directory exists
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    
    try:
        # ADDED: encoding='utf-8'
        with open(input_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        # ADDED: encoding='utf-8'
        with open(log_file, "w", encoding='utf-8') as log:
            log.write(f"Error loading {input_path}: {e}\n")
        print(f"Error: Could not read {input_path}. See {log_file} for details.")
        return

    print("Running solver... Check timetable_engine.log for progress.")

    # ADDED: encoding='utf-8'
    with open(log_file, "w", encoding='utf-8') as log:
        log.write("=====================================================================\n")
        log.write(f"⏳ [PHASE 1] Attempting Strict Solve for {input_path}...\n")
        log.write("=====================================================================\n")
        
    status, solver, deficit_trackers, group_is_atomic, group_batches, schedules, score = build_and_solve(data, diagnostic_mode=False, max_cost=max_cost)

    if status == cp_model.INFEASIBLE:
        # ADDED: encoding='utf-8'
        with open(log_file, "a", encoding='utf-8') as log:
            log.write("\n❌ Strict constraints are INFEASIBLE.\n")
            log.write("⚠️ [PHASE 2] Switching to Diagnostic Mode to generate Best-Effort Schedule...\n\n")
        
        status, solver, deficit_trackers, group_is_atomic, group_batches, schedules, score = build_and_solve(data, diagnostic_mode=True, max_cost=max_cost)
        
        if status in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
            # ADDED: encoding='utf-8'
            with open(log_file, "a", encoding='utf-8') as log:
                log.write("=====================================================================\n")
                log.write("🚨 BOTTLENECK REPORT: UNFULFILLED REQUIREMENTS 🚨\n")
                log.write("=====================================================================\n")
                bottleneck_found = False
                
                for b_id, subject, req, deficit_var in deficit_trackers:
                    deficit_val = solver.Value(deficit_var)
                    if deficit_val > 0:
                        bottleneck_found = True
                        scheduled = req - deficit_val
                        log.write(f"⚠️  Batch {b_id} | Subject: {subject:<10} | Requires: {req} slots | Scheduled: {scheduled} slots (Short by {deficit_val})\n")
                
                if not bottleneck_found:
                    log.write("Could not isolate a specific batch requirement. The bottleneck may be structural.\n")
                
                log.write(f"\n✅ Generated 1 BEST-EFFORT output file: 'best_effort_timetable.json'\n")

            best_effort_path = os.path.join(output_dir, f"best_effort_timetable_{timestamp}.json")
            
            save_to_json(schedules[0], group_batches, data, False,best_effort_path ) # this only 
            return
        else:
            # ADDED: encoding='utf-8'
            with open(log_file, "a", encoding='utf-8') as log:
                log.write("Fatal Error: The configuration is too broken even for diagnostic mode.\n")
            return

    elif status in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
        # ADDED: encoding='utf-8'
        with open(log_file, "a", encoding='utf-8') as log:
            log.write(f"\n✅ Schedule generation successful! Status: {solver.StatusName(status)}\n")
            log.write(f"Total Composite Penalty Score achieved: {score}\n")
            log.write(f"Generated {len(schedules)} distinct optimal timetable variants.\n")

        for idx, active_assignments in enumerate(schedules):
            filename = os.path.join(output_dir, f"timetable_variant_{idx + 1}_{timestamp}.json")
            save_to_json(active_assignments, group_batches, data, True, filename)            
            # ADDED: encoding='utf-8'
            with open(log_file, "a", encoding='utf-8') as log:
                log.write(f" -> Exported alternative schedule to: {filename}\n")

#if __name__ == "__main__":
#    if len(sys.argv) not in [2, 3]:
 #       print("Usage: python scheduler.py <config_file.json> [max_cost]")
#        sys.exit(1)
        
 #   config_filename = sys.argv[1]
 #   max_cost = float(sys.argv[2]) if len(sys.argv) == 3 else float('inf')
 #   solve_schedule(config_filename, max_cost)

if __name__ == "__main__":
    # Ensure we have enough arguments
    if len(sys.argv) < 5:
        print("Usage: python tt.py <input> <report> <output> <log>")
        sys.exit(1)
        
    input_f = sys.argv[1]
    report_f = sys.argv[2]
    output_dir = sys.argv[3]
    log_f = sys.argv[4]
    
    # Run the solver exactly once
    solve_schedule(input_f, report_f, output_dir, log_f)
## 1. Upload Obstacle CSV

**Endpoint:** `POST /api/upload`  
**Description:** Accepts a cleaned obstacle CSV file, validates its structure and coordinates, and stores the dataset in memory (or session) for subsequent simulation calls.

**Request:**  
- `Content-Type: multipart/form-data`  
- Form field: `file` (the CSV file)

**Expected CSV Columns:**  
| Column | Type | Description |
|--------|------|-------------|
| `id` | string/integer | Unique obstacle identifier (from original sheets) |
| `name` | string | Obstacle name/description |
| `type` | string | e.g., Building, Tree, Antenna |
| `latitude` | float (decimal degrees) | WGS84 latitude |
| `longitude` | float (decimal degrees) | WGS84 longitude |
| `elevation` | float (meters) | Ground elevation above mean sea level |
| `height` | float (meters) | Physical height of obstacle above ground |



## 2. Run Simulation

**Endpoint:** `POST /api/simulate`  
**Description:** Uses the previously uploaded dataset, applies the Obstacle Limitation Surface (OLS) based on user-selected parameters, and returns enriched JSON with computed distances, offsets, clearances, and risk tags for each obstacle.



## 3. Take‑Off Obstacle Analysis (Specialised Algorithm)

**Endpoint:** `POST /api/analyze/takeoff`  
**Description:** Implements the step‑by‑step algorithm you specified for take‑off obstacle identification. It uses the previously uploaded obstacle dataset, projects them onto the runway centreline, and applies a **1.2° vertical slope** with a **12.5° lateral divergence**. It then selects:

1. All obstacles that **penetrate** the 1.2° surface and lie within the lateral bounds.
2. The **first (closest) penetrating obstacle**.
3. From obstacles **farther than 300 m** beyond that first one, those **taller** than the first one.
4. Among those, the **highest** obstacle (or the closest tie) up to **10 000 m**.

---

### Request Body (JSON)

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `slope_angle_deg` | float | `1.2` | Vertical climb slope in degrees |
| `lateral_divergence_deg` | float | `12.5` | Half‑angle of lateral expansion from centreline |
| `initial_half_width_m` | float | `900.0` | Minimum half‑width (keeps the corridor at least 1800 m wide near the runway) |
| `segment_skip_m` | float | `300.0` | Longitudinal distance to skip **after** the first penetrating obstacle |
| `max_distance_m` | float | `10000.0` | Maximum longitudinal distance to consider from the threshold |

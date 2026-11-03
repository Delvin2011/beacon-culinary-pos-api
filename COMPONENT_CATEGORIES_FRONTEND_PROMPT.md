# Component Categories — Frontend

Adds a flat list of **component categories** (Meat, Chicken, Fish, Starch, ...), a category on every component, and a
**category filter and name search** on the Components tab of Catalogue Management. The backend is done and tested
(migration `V100__component_categories.sql`). Everything below has been checked against the actual endpoints.

> **Nothing breaks if this ships after the backend.** `categoryId` is optional on component create and update, and an
> update that leaves it out keeps the current category. The current Components tab keeps working unchanged.

These are **component** categories, separate from the **meal** catalogue tree (`/admin/catalog/categories`: Set
meals, Light meals, Beverages). Don't mix the two in the UI or in shared state.

## Ground rules

**Permissions.** U2 (the permissions matrix) is not merged. Use these role checks:

| Action | Roles today |
|---|---|
| See component categories, filter and search components | `ADMIN`, `HEAD_CHEF` |
| Add / edit / deactivate component categories | `ADMIN` |
| Set a component's category (component form) | `ADMIN` (same as editing a component today) |

Hide what the user can't do. If the server still refuses (`403`), show a plain message.

**Errors.** Rule errors come back as `{ "error": "<message>", "code": "<CODE>" }`: branch on `code`, show `error`.
Field validation errors come back as a map: `{ "name": "name is required" }`.

## 0. What the backend gives you (summary)
| Need | Call | Notes |
|---|---|---|
| Category list (dropdowns) | `GET /admin/component-categories` | active only, sorted by `sortOrder`, then name |
| Category list (management) | `GET /admin/component-categories?includeInactive=true` | includes inactive ones |
| Add a category | `POST /admin/component-categories { name, sortOrder }` | `201` with the category |
| Edit a category | `PUT /admin/component-categories/{id} { name, sortOrder, active }` | no delete |
| Components, filtered | `GET /admin/component-catalog?categoryId=&uncategorised=&name=` | all optional, combined with AND |
| Create / edit a component | `POST /admin/component-catalog`, `PUT /admin/component-catalog/{id}` | optional `categoryId` |

Seeded categories, in order: **Meat · Chicken · Fish · Starch · Vegetables & relishes · Sauces & gravies ·
Desserts & fruit**. The existing 25 components are already assigned.

## Types
```ts
type ComponentCategoryRef = { id: number; name: string; sortOrder: number };

type ComponentCategory = ComponentCategoryRef & {
  active: boolean;
  componentCount: number; // active components in this category
};

type ComponentCatalog = {
  id: number;
  name: string;
  extraPrice: number;
  active: boolean;
  category: ComponentCategoryRef | null; // NEW, null = uncategorised
};

type CreateComponentRequest = { name: string; extraPrice: number; categoryId?: number };
type UpdateComponentRequest = { name: string; extraPrice: number; active: boolean; categoryId?: number };
```
`category` also appears on the components nested in meal responses (`GET /admin/meal-catalog`, `components[]`).

## 1. Catalogue Management, Components tab: filter and search
- Add a toolbar above the list:
  - **Category** dropdown: `All` · each active category (from `GET /admin/component-categories`) · `Uncategorised`.
    - `All` sends no category parameter.
    - A category sends `?categoryId={id}`.
    - `Uncategorised` sends `?uncategorised=true`.
  - **Search** box: sends `?name=<text>`. Matches part of the name, case-insensitive. Debounce about 300 ms; an empty
    box sends nothing.
  - The two combine (e.g. `?categoryId=4&name=rice`).
- Keep the filter in the URL query string so a refresh or a back navigation keeps it.
- The list comes back **sorted by name**. Add a **Category** column showing `category.name`, or a muted
  "Uncategorised" when `category` is `null`.
- Optional: a "Group by category" view with section headings in `category.sortOrder` order, `Uncategorised` last.
- Empty result: "No components match." with a **Clear filters** link.
- Inactive components are still returned (as today); keep however the tab shows them now.
- Add a **Categories** button on the toolbar that opens the screen in §3 (`ADMIN` only; Head Chef sees it read-only or
  not at all, your choice).

## 2. Component form (create and edit)
- Add a **Category** dropdown filled with **active** categories. Send the chosen id as `categoryId`.
- The backend allows no category, but **make it required in the UI** so new components don't end up uncategorised.
- **Edit:** pre-select `category.id`. If the component's current category is inactive (it won't be in the active
  list), show it as a disabled option labelled "<name> (inactive)" so the form doesn't look empty.
- Sending no `categoryId` (or `null`) on edit **keeps** the current category. A component can't be made uncategorised
  from the form; that's intended.
- Error `400 CATEGORY_INVALID`: the category was deactivated (or removed) after the form loaded. Reload the dropdown and
  show "That category is no longer available. Choose another."

## 3. Component categories screen (`ADMIN`)
A simple list editor. Reuse the look of the meal Categories screen (`CATALOGUE_AND_BAR_FRONTEND_PROMPT.md`, §2) or the
Count Sheet Categories screen, but this list is **flat** (no parents, no fixed rows).

- Load `GET /admin/component-categories?includeInactive=true`:
  ```json
  [{ "id": 1, "name": "Meat", "sortOrder": 1, "active": true, "componentCount": 5 },
   { "id": 4, "name": "Starch", "sortOrder": 4, "active": true, "componentCount": 7 }]
  ```
  Columns: name, sort order, components (`componentCount`), status. Inactive rows show dimmed with **Reactivate**.
- **Add:** `POST /admin/component-categories { "name": "Vegetables", "sortOrder": 8 }` returns `201` with the row.
  Default `sortOrder` to the current max + 1.
- **Rename / reorder / deactivate:** `PUT /admin/component-categories/{id} { name, sortOrder, active }`.
  **Always send all three fields** (a missing `active` defaults to `true`). There's no bulk reorder: moving a row
  means one `PUT` per row whose `sortOrder` changes.
- No delete.
- Errors:
  - `409 CATEGORY_NAME_TAKEN`: same name already exists (case-insensitive). Show it on the name field.
  - `409 CATEGORY_HAS_ITEMS`: deactivating a category that still has active components. Show the message (it gives the
    count). You may also disable **Deactivate** when `componentCount > 0`, with the tooltip "Move its components first."
  - `404`: the category no longer exists; reload the list.
- After any change, refresh the dropdowns on the Components tab and the component form (or invalidate their cache).

## 4. Optional: meal form component picker
On the meal form (Meals tab), the component picker can group options by `category.name` or offer the same category
filter, using `GET /admin/component-catalog?categoryId=`. Nothing else on the meal form changes.

## 5. Bulk Upload Components (CSV)
`POST /admin/component-catalog/bulk-import` (multipart, field `file`) now reads an **optional `Category` column**
(the header `Component Category` also works). Nothing else about the upload changes.

- Update the **template / sample CSV** and the help text on the upload dialog:
  ```csv
  COMPONENT,CATEGORY,INGREDIENT (NAME),UNIT,COUNT SHEET,Quantities,Batch Size,Per Portion Price (R)
  Beef Stew,Meat,Beef Chuck,kg,meat,2.5,10,25
  Beef Stew,,Onion,kg,fveg,0.8,10,25
  Pap,Starch,Maize Meal,kg,drystock,1.2,20,10
  ```
- Help text to show:
  - "Category is the component's category (e.g. Meat, Starch). It's matched by name, ignoring case. You only need it
    on one row per component."
  - "Leave it blank to keep a component's current category; new components without one are Uncategorised."
  - "Count Sheet is the ingredient's count sheet category, not the component's."
- List the valid category names next to the template (from `GET /admin/component-categories`), so users know what to
  type.
- Errors come back as today: `400 { "errors": ["Row 3: ...", ...] }`, and **nothing is saved**. Two new messages:
  - `Row N: component category 'Seafood' must be one of [Meat, Chicken, ...], or left blank.`
  - `Row N: component category 'Starch' conflicts with category 'Meat' already used for component 'Beef Stew' earlier in the file.`
- The result's `components[]` rows now include `category` (`{ id, name, sortOrder }` or `null`). Show it in the
  import summary table.
- After a successful upload, refresh the Components tab and the category counts.

## Acceptance
- [ ] The Components tab shows a Category column; uncategorised components say "Uncategorised".
- [ ] The category dropdown and the search box filter the list, alone and together, and survive a page refresh.
- [ ] Creating a component requires a category in the UI and saves it; editing shows and changes it.
- [ ] Editing a component's name or price leaves its category alone.
- [ ] The Categories screen adds, renames, reorders, deactivates and reactivates categories, with the errors above.
- [ ] Head Chef can filter and search but can't change categories; Cashier has no access.
- [ ] The bulk upload template and help text include the Category column; the summary shows each component's category.

## Test scenarios (dev database after V100)
- Filter **Starch** → 7 components (Breyani Rice, Chips, Mash Potato, Pap, Pasta, Rice, Spaghetti).
- Filter **Sauces & gravies** and search "curry" → Beef Curry Sauce and Chicken Curry Sauce.
- Search "gravy" with **All** → Gravy, Onion Gravy, Tomato & Onion Gravy.
- Filter **Uncategorised** → empty list ("No components match.").
- Add a category "Vegetables"; create "Butternut" under it → it shows under Vegetables, and the category's count is 1.
- Try to deactivate "Fish" → `CATEGORY_HAS_ITEMS` ("'Fish' has 1 active component. Move or deactivate them first.").
- Add a category named "meat" → `CATEGORY_NAME_TAKEN`.
- Move "Bolognaise Sauce" to Meat → it disappears from the Sauces filter and appears under Meat.
- Upload the sample CSV above → Beef Stew is under Meat, Pap under Starch.
- Upload a new component with a blank Category → it appears under **Uncategorised**.
- Re-upload Beef Stew with a blank Category → it stays under Meat.
- Upload with Category "Seafood" → the error lists the valid names; nothing is saved.
- Log in as Head Chef → the filter and search work; the Categories button and the component form's save are hidden or
  refused.

## Not in this change
Creating categories from the CSV (an unknown name is an error, not a new category), clearing a category via the CSV,
nested categories, and a category on daily component stock or the till's extras.

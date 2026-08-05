Enclose records a walking route. The user presses Start, walks a loop on foot, and the app draws the path. When the loop closes, they claim the area inside it.

Why it must start immediately. The recording is the walk. If the start is delayed, the first part of the route is missing, and the path no longer begins where the user began. The loop is a closed shape it only works if the start point and the end point meet. A missing start means there is no loop to close, and the walk cannot be claimed.

Why it cannot be paused. Every gap in the path is ground the app did not see. The app measures distance and the shape of the enclosed area from the recorded points. A pause leaves a straight line across streets the user did not walk in a straight line, which makes the claimed area wrong. It also makes cheating possible: we cannot tell a paused walk from a drive.

Why it cannot be restarted later. A walk cannot be repeated from a chair. If we lose it, the user has to go outside and walk the whole route again, which  can be two hours. This is the one thing in the app the user spent real effort on, so losing it is the worst failure we can have.

How long it runs. 
Only between Start and Stop, typically 20 minutes to two hours. It never starts by itself. If the system restarts the service and there  is no walk in progress, the service shuts itself down immediately rather than using location for nothing.

What the user sees. An ongoing notification, "Enclose is tracking your walk", for the whole time. The path is drawn live on the map, and an optional floating window shows it over other apps.

## License

Copyright (C) 2026 dimitrmo

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU Affero General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License along
with this program. If not, see <https://www.gnu.org/licenses/>.

SPDX-License-Identifier: `AGPL-3.0-or-later`. The full text is in [LICENSE](LICENSE).

The AGPL's section 13 applies to the sync seam: `RemoteSyncApi` currently has no
backend bound, but anyone who binds one and lets users interact with this app
over a network owes those users the corresponding source of the whole modified
work, server side included.
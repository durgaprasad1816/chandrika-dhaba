# Chandrika Dhaba image guide

Food photos are bundled in `src/main/resources/web/img/dishes/`. They are local assets and work offline. Replace a JPG with your own photo using the same filename to keep the menu mapping. The JavaScript name mapping is in `src/main/resources/web/js/customer.js` in `fallbackImage(it)`.

The welcome animation has been changed to a single centered composition: food image, centered brand name, and a progress bar. This removes the previous side-by-side/split look.

Run with `run.bat` on Windows, or `mvn clean package` and `java -jar target/chandrika-dhaba.jar`. Back up your `data` folder before updating an existing installation.

const sideMenu = document.querySelector("aside");
const menuBtn = document.querySelector("#menu-btn");
const closeBtn = document.querySelector("#close-btn");
const themeToggler = document.querySelector(".theme-toggler");
const lightIcon = themeToggler.children[0];
const darkIcon = themeToggler.children[1];

// show sidebar
menuBtn.addEventListener('click', () => {
  sideMenu.style.display = 'block';
});

// close sidebar
closeBtn.addEventListener('click', () => {
  sideMenu.style.display = 'none';
})

/// ===== DARK MODE WITH SAVE =====

// apply saved theme on page load
function applyTheme(theme) {

    if (theme === "dark") {
        document.documentElement.classList.add("dark-theme-variables");
        darkIcon.classList.add("active");
        lightIcon.classList.remove("active");
    } else {
        document.documentElement.classList.remove("dark-theme-variables");
        lightIcon.classList.add("active");
        darkIcon.classList.remove("active");
    }
}

// Apply on load
document.addEventListener("DOMContentLoaded", function () {

    const savedTheme = localStorage.getItem("theme") || "light";
    applyTheme(savedTheme);

});

// Toggle click
themeToggler.addEventListener("click", () => {

    const isDark = document.documentElement.classList.contains("dark-theme-variables");
    const newTheme = isDark ? "light" : "dark";
    localStorage.setItem("theme", newTheme);
    applyTheme(newTheme);

});


package com.admtechhub.maestrohr.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminWebController {

    @GetMapping("/pricing")
    public String pricingPage(Model model) {
        model.addAttribute("pageTitle", "Admin - Pricing Management");
        return "admin/pricing";
    }
}
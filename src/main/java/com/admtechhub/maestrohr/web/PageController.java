package com.admtechhub.maestrohr.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/audit")
    public String audit() {
        return "forward:/audit.html";
    }
}